package kamon.ganglia

import java.net.InetSocketAddress
import java.net.InetAddress
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.collection.JavaConverters._

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import akka.io.Tcp._
import akka.io.{IO, Udp}
import akka.util.ByteString
import kamon.Kamon
import kamon.metric._
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.util.ConfigTools.Syntax
import kamon.metric.instrument.{Counter, Histogram, UnitOfMeasurement}
import kamon.util.NeedToScale
import org.slf4j.LoggerFactory
import org.acplt.oncrpc.XdrBufferEncodingStream
import info.ganglia.gmetric4j.gmetric.GMetricSlope
import info.ganglia.gmetric4j.gmetric.GMetricType
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_extra_data
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_gmetric_string
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_metadata_message
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_metadata_msg
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_metadatadef
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_metric_id
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_msg_formats
import info.ganglia.gmetric4j.xdr.v31x.Ganglia_value_msg


object Ganglia extends ExtensionId[GangliaExtension] with ExtensionIdProvider {
  override def lookup(): ExtensionId[_ <: Extension] = Ganglia
  override def createExtension(system: ExtendedActorSystem): GangliaExtension = new GangliaExtension(system)
}

class GangliaExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  private val log = LoggerFactory.getLogger(classOf[GangliaExtension])
  log.info("Starting the Kamon(Ganglia) extension")

  private implicit val as = system
  private val config = system.settings.config
  private val gangliaConfig = config.getConfig("kamon.ganglia")

  private val metadataMessageInterval = gangliaConfig.getInt("meta-data-message-interval")
  private val hostname = gangliaConfig.getString("hostname")
  private val port = gangliaConfig.getInt("port")
  private val metricPrefix = gangliaConfig.getString("metric-name-prefix")
  private val failureRetryDelay = {
    val duration = gangliaConfig.getDuration("failure-retry-delay")
    FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS)
  }
  private val bufferSize = gangliaConfig.getInt("buffer-size")

  private val retryBufferSize = gangliaConfig.getInt("write-retry-buffer-size")

  private val metricsSender = system.actorOf(Props(
    new GangliaClient(hostname, port, failureRetryDelay, bufferSize, retryBufferSize, metadataMessageInterval, metricPrefix)),
    "kamon-ganglia")

  private val gangliaClient = gangliaConfig match {
    case NeedToScale(scaleTimeTo, scaleMemoryTo) =>
      system.actorOf(MetricScaleDecorator.props(scaleTimeTo, scaleMemoryTo, metricsSender), "ganglia-metric-scale-decorator")
    case _ => metricsSender
  }

  private val subscriptions = gangliaConfig.getConfig("subscriptions")

  subscriptions.firstLevelKeys.foreach { subscriptionCategory =>
    subscriptions.getStringList(subscriptionCategory).asScala.foreach { pattern =>
      Kamon.metrics.subscribe(subscriptionCategory, pattern, gangliaClient, permanently = true)
    }
  }

}

class GangliaClient(host: String,
                    port: Int,
                    connectionRetryDelay: FiniteDuration,
                    bufferSize: Int,
                    writeRetryBufferSize: Int,
                    metadataMessageInterval: Int,
                    metricPrefix: String) extends Actor {

  import GangliaClient._
  import context.dispatcher

  private val TMAX = 60
  private val DMAX = 0

  private val log = LoggerFactory.getLogger(classOf[GangliaClient])

  private var failedWrites = Seq.empty[Write]

  private val xdr = new XdrBufferEncodingStream(bufferSize)
  private val metricCounterMap = new java.util.HashMap[String, Integer]()

  private val remote = new InetSocketAddress(host, port)
  private val localHostName = InetAddress.getLocalHost.getHostName

  override def receive: Receive = disconnected

  override def preStart(): Unit = {
    self ! GangliaClient.InitiateConnection
  }

  def disconnected: Actor.Receive = discardSnapshots orElse {
    case GangliaClient.InitiateConnection =>
      implicit val as = context.system
      IO(Udp) ! Udp.SimpleSender
      context.become(connecting)

    case CommandFailed(write: Write) =>
      bufferFailedWrite(write)
  }

  def discardSnapshots: Actor.Receive = {
    case _: TickMetricSnapshot =>
      log.warn("UDP sender is not prepared yet, discarding TickMetricSnapshot")
  }

  def connecting: Actor.Receive = discardSnapshots orElse {
    case CommandFailed(_: Connect) =>
      log.warn("Unable to initialize UDP sender, retrying in {}", connectionRetryDelay)
      startReconnecting()

    case CommandFailed(write: Write) =>
      log.warn("Write command to Ganglia failed, adding the command to the retry buffer")
      bufferFailedWrite(write)

    case Udp.SimpleSenderReady =>
      log.info("UDP sender is prepared to send data to Ganglia")
      context.become(sending(sender()))
  }

  def sending(connection: ActorRef): Actor.Receive = {
    case snapshot: TickMetricSnapshot =>
      dispatchSnapshot(connection, snapshot)

    case _: ConnectionClosed =>
      log.warn("Disconnected from Graphite, trying to reconnect in {}", connectionRetryDelay)
      startReconnecting()

    case CommandFailed(write: Write) =>
      log.warn("Write command to Graphite failed, adding the command to the retry buffer")
      bufferFailedWrite(write)
      startReconnecting()
  }

  def bufferFailedWrite(write: Write): Unit = {
    if(failedWrites.size >= writeRetryBufferSize) {
      log.info("The retry buffer is full, discarding a failed write command")
      failedWrites = failedWrites.tail
    }

    failedWrites = failedWrites :+ write
  }

  def flushFailedWrites(connection: ActorRef): Unit = {
    log.info("Flushing {} writes from the retry buffer", failedWrites.size)
    failedWrites.foreach(w => connection ! w)
    failedWrites = Seq.empty[Write]
  }

  def startReconnecting(): Unit = {
    context.become(disconnected)
    context.system.scheduler.scheduleOnce(connectionRetryDelay, self, GangliaClient.InitiateConnection)
  }

  private def dispatchSnapshot(connection: ActorRef, snapshot: TickMetricSnapshot): Unit = {
    for ((entity, entitySnapshot) <- snapshot.metrics) {
      dispatchHistograms(connection, entity, entitySnapshot.histograms)
      dispatchGauges(connection, entity, entitySnapshot.gauges)
      dispatchMinMaxCounters(connection, entity, entitySnapshot.minMaxCounters)
      dispatchCounters(connection, entity, entitySnapshot.counters)
    }
  }

  private def dispatchCounters(connection: ActorRef, entity: Entity, counters: Map[CounterKey, Counter.Snapshot]) = counters foreach {
    case (counterKey, snap) =>
      val group = genName(metricPrefix, entity.name)

      announce(group, group, snap.count, counterKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
  }

  private def dispatchHistograms(connection: ActorRef, entity: Entity, histograms: Map[HistogramKey, Histogram.Snapshot]) = histograms foreach {
    case (histogramKey, snap) =>
      val group = genName(metricPrefix, entity.name)

      announce(genName(metricPrefix, entity.name, "count"), group, snap.numberOfMeasurements, histogramKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "min"), group, snap.min, histogramKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "max"), group, snap.max, histogramKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "p50"), group, snap.percentile(50d), histogramKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "p90"), group, snap.percentile(90d), histogramKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "p99"), group, snap.percentile(99d), histogramKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "sum"), group, snap.sum, histogramKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
  }

  private def dispatchGauges(connection: ActorRef, entity: Entity, gauges: Map[GaugeKey, Histogram.Snapshot]) = gauges foreach {
    case (gaugeKey, snap) =>
      val group = genName(metricPrefix, entity.name)

      announce(genName(metricPrefix, entity.name, "min"), group, snap.min, gaugeKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "max"), group, snap.max, gaugeKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "sum"), group, snap.sum, gaugeKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "avg"), group, average(snap), gaugeKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
  }

  private def dispatchMinMaxCounters(connection: ActorRef, entity: Entity, minMaxCounters: Map[MinMaxCounterKey, Histogram.Snapshot]) = minMaxCounters foreach {
    case (minMaxCounterKey, snap) =>
      val group = genName(metricPrefix, entity.name)

      announce(genName(metricPrefix, entity.name, "min"), group, snap.min, minMaxCounterKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "max"), group, snap.max, minMaxCounterKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
      announce(genName(metricPrefix, entity.name, "avg"), group, average(snap), minMaxCounterKey.unitOfMeasurement).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
  }

  private def isTimeToSendMetadata(metricName: String) = {
    var ret = false
    var counter = metricCounterMap.get(metricName)
    if (counter == null) {
      counter = 0
      ret = true
    } else {
      counter += 1
      if (counter >= metadataMessageInterval) {
        counter = 0
        ret = true
      }
    }
    metricCounterMap.put(metricName, counter)
    ret
  }

  @throws[Exception]
  private def announce(name: String, groupName: String, value: Long, units: UnitOfMeasurement): Seq[ByteString] = {
    val metric_id = new Ganglia_metric_id
    metric_id.spoof = false
    metric_id.host = localHostName
    metric_id.name = name

    if (isTimeToSendMetadata(name)) {
      encodeGMetric(metric_id, name, groupName, value.toString, GMetricType.DOUBLE, units.name, GMetricSlope.BOTH, TMAX, DMAX)
      val metaInfo = ByteString.fromArray(xdr.getXdrData, 0, xdr.getXdrLength)

      encodeGValue(metric_id, value.toString)
      val data = ByteString.fromArray(xdr.getXdrData, 0, xdr.getXdrLength)

      Seq(metaInfo, data)
    } else {
      encodeGValue(metric_id, value.toString)
      Seq(ByteString.fromArray(xdr.getXdrData, 0, xdr.getXdrLength))
    }
  }

  @throws[Exception]
  private def encodeGMetric(metric_id: Ganglia_metric_id, groupName: String, name: String, value: String,
                            `type`: GMetricType, units: String, slope: GMetricSlope, tmax: Int, dmax: Int) = {
    val metadata_message = new Ganglia_metadata_message
    val extra_data_array = new Array[Ganglia_extra_data](3)

    val extra_data1 = new Ganglia_extra_data
    extra_data_array(0) = extra_data1
    extra_data1.name = "GROUP"
    extra_data1.data = groupName

    val extra_data2 = new Ganglia_extra_data
    extra_data_array(1) = extra_data2
    extra_data2.name = "TITLE"
    extra_data2.data = name

    val extra_data3 = new Ganglia_extra_data
    extra_data_array(2) = extra_data3
    extra_data3.name = "DESC"
    extra_data3.data = name

    metadata_message.metadata = extra_data_array
    metadata_message.name = name
    metadata_message.`type` = `type`.getGangliaType
    metadata_message.units = units
    metadata_message.slope = slope.getGangliaSlope
    metadata_message.tmax = tmax
    metadata_message.dmax = dmax

    val metadatadef = new Ganglia_metadatadef
    metadatadef.metric_id = metric_id
    metadatadef.metric = metadata_message

    val metadata_msg = new Ganglia_metadata_msg
    metadata_msg.id = Ganglia_msg_formats.gmetadata_full
    metadata_msg.gfull = metadatadef

    xdr.beginEncoding(remote.getAddress, remote.getPort)
    metadata_msg.xdrEncode(xdr)
    xdr.endEncoding()
  }

  @throws[Exception]
  private def encodeGValue(metric_id: Ganglia_metric_id, value: String) = {

    val value_msg = new Ganglia_value_msg
    value_msg.id = Ganglia_msg_formats.gmetric_string

    val str = new Ganglia_gmetric_string
    str.str = value
    str.metric_id = metric_id
    str.fmt = "%s"
    value_msg.gstr = str

    xdr.beginEncoding(remote.getAddress, remote.getPort)
    value_msg.xdrEncode(xdr)
    xdr.endEncoding()
  }
}

object GangliaClient {

  case object InitiateConnection

  private def sanitize(value: String): String =
    value.replace('/', '_').replace('.', '_')

  private def genName(prefix: String, name: String): String =
    new java.lang.StringBuilder()
      .append(prefix)
      .append(".")
      .append(sanitize(name))
      .toString

  private def genName(prefix: String, name: String, subname: String): String =
    new java.lang.StringBuilder()
      .append(prefix)
      .append(".")
      .append(sanitize(name))
      .append(".")
      .append(sanitize(subname))
      .toString

  private def average(snapshot: Histogram.Snapshot): Long =
    if(snapshot.numberOfMeasurements > 0) snapshot.sum / snapshot.numberOfMeasurements else 0
}
