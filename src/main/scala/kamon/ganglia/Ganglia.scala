package kamon.ganglia

import java.net.InetSocketAddress
import java.net.InetAddress
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.concurrent.Await

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp._
import akka.io.{IO, Udp}
import akka.util.ByteString
import com.typesafe.config.Config
import info.ganglia.gmetric4j.gmetric.{GMetricSlope, GMetricType}
import info.ganglia.gmetric4j.xdr.v31x._
import kamon.{Kamon, MetricReporter}
import kamon.metric._
import org.slf4j.LoggerFactory
import org.acplt.oncrpc.XdrBufferEncodingStream


class GangliaMetricReporter extends MetricReporter {

  @volatile
  private var as: Option[ActorSystem] = None

  @volatile
  private var client: Option[ActorRef] = None

  private var settings = GangliaMetricReporter.readSettings(Kamon.config())

  override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
    for {
      s <- as
      c <- client
    } {
      implicit val sys: ActorSystem = s
      c ! snapshot.metrics
    }
  }

  override def start(): Unit = {
    val actorSystem = ActorSystem("ganglia-reporter")
    val cl = actorSystem.actorOf(GangliaClient.props(settings.hostname,
      settings.port,
      settings.failureRetryDelay,
      settings.bufferSize,
      settings.retryBufferSize,
      settings.metadataMessageInterval,
      settings.metricPrefix), "client")

    as = Some(actorSystem)
    client = Some(cl)
  }

  override def stop(): Unit = {
    as.foreach { s =>
      Await.ready(s.terminate(), Duration.Inf)
    }
  }

  override def reconfigure(config: Config): Unit = {
    settings = GangliaMetricReporter.readSettings(config)
  }

}

object GangliaMetricReporter {

  final case class Settings(
    metricPrefix: String,
    hostname: String,
    port: Int,
    metadataMessageInterval: Int,
    failureRetryDelay: FiniteDuration,
    bufferSize: Int,
    retryBufferSize: Int)

  def readSettings(config: Config): Settings = {
    Settings(
      metricPrefix = config.getString("kamon.ganglia.metric-name-prefix"),
      hostname = config.getString("kamon.ganglia.hostname"),
      port = config.getInt("kamon.ganglia.port"),
      metadataMessageInterval = config.getInt("kamon.ganglia.meta-data-message-interval"),
      failureRetryDelay = {
        val duration = config.getDuration("kamon.ganglia.failure-retry-delay")
        FiniteDuration(duration.toMillis, TimeUnit.MILLISECONDS)
      },
      bufferSize = config.getInt("kamon.ganglia.buffer-size"),
      retryBufferSize = config.getInt("kamon.ganglia.write-retry-buffer-size"))
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
      implicit val as: ActorSystem = context.system
      IO(Udp) ! Udp.SimpleSender
      context.become(connecting)

    case CommandFailed(write: Write) =>
      bufferFailedWrite(write)
  }

  def discardSnapshots: Actor.Receive = {
    case _: PeriodSnapshot =>
      log.warn("XDR sender is not prepared yet, discarding TickMetricSnapshot")
  }

  def connecting: Actor.Receive = discardSnapshots orElse {
    case CommandFailed(_: Connect) =>
      log.warn("Unable to initialize XDR sender, retrying in {}", connectionRetryDelay)
      startReconnecting()

    case CommandFailed(write: Write) =>
      log.warn("Write command to Ganglia failed, adding the command to the retry buffer")
      bufferFailedWrite(write)

    case Udp.SimpleSenderReady =>
      log.info("XDR sender is prepared to send data to Ganglia")
      context.become(sending(sender()))
  }

  def sending(connection: ActorRef): Actor.Receive = {
    case snapshot: PeriodSnapshot =>
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

  private def dispatchSnapshot(connection: ActorRef, snapshot: PeriodSnapshot): Unit = {
    dispatchMetricValue(connection, snapshot.metrics.gauges)
    dispatchMetricValue(connection, snapshot.metrics.counters)
    dispatchHistograms(connection, snapshot.metrics.histograms)
    dispatchMinMaxCounters(connection, snapshot.metrics.rangeSamplers)
  }

  private def dispatchMetricValue(connection: ActorRef, gauges: Seq[MetricValue]): Unit = gauges foreach { gauge =>
    val group = genName(metricPrefix, gauge.name)
    announce(genName(metricPrefix, gauge.name, "value"), group, gauge.value, gauge.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }

    for (tag <- gauge.tags) {
      val subgroup = genName(metricPrefix, gauge.name, tag._1, tag._2)
      announce(genName(metricPrefix, gauge.name, tag._1, tag._2, "value"), subgroup, gauge.value, gauge.unit).foreach { data =>
        connection ! Udp.Send(data, remote)
      }
    }
  }

  private def dispatchHistograms(connection: ActorRef, histograms: Seq[MetricDistribution]): Unit = histograms foreach { hist =>
    val group = genName(metricPrefix, hist.name)

    announce(genName(metricPrefix, hist.name, "count"), group, hist.distribution.count, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "min"), group, hist.distribution.min, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "max"), group, hist.distribution.max, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "p50"), group, hist.distribution.percentile(50d).value, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "p90"), group, hist.distribution.percentile(90d).value, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "p99"), group, hist.distribution.percentile(99d).value, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "sum"), group, hist.distribution.sum, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
  }

  private def dispatchMinMaxCounters(connection: ActorRef, histograms: Seq[MetricDistribution]): Unit = histograms foreach { hist =>
    val group = genName(metricPrefix, hist.name)

    announce(genName(metricPrefix, hist.name, "min"), group, hist.distribution.min, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "max"), group, hist.distribution.max, hist.unit).foreach { data =>
      connection ! Udp.Send(data, remote)
    }
    announce(genName(metricPrefix, hist.name, "avg"), group, hist.distribution.sum / hist.distribution.count, hist.unit).foreach { data =>
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

  private def announce(name: String, groupName: String, value: Long, units: MeasurementUnit): Seq[ByteString] = {
    val metric_id = new Ganglia_metric_id
    metric_id.spoof = false
    metric_id.host = localHostName
    metric_id.name = name

    if (isTimeToSendMetadata(name)) {
      encodeGMetric(metric_id, name, groupName, value.toString, GMetricType.DOUBLE, units.magnitude.name, GMetricSlope.BOTH, TMAX, DMAX)
      val metaInfo = ByteString.fromArray(xdr.getXdrData, 0, xdr.getXdrLength)

      encodeGValue(metric_id, value.toString)
      val data = ByteString.fromArray(xdr.getXdrData, 0, xdr.getXdrLength)

      Seq(metaInfo, data)
    } else {
      encodeGValue(metric_id, value.toString)
      Seq(ByteString.fromArray(xdr.getXdrData, 0, xdr.getXdrLength))
    }
  }

  private def encodeGMetric(metric_id: Ganglia_metric_id, groupName: String, name: String, value: String,
                            `type`: GMetricType, units: String, slope: GMetricSlope, tmax: Int, dmax: Int): Unit = {

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

  private def encodeGValue(metric_id: Ganglia_metric_id, value: String): Unit = {

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

  private def genName(prefix: String, name: String, subnames: String*): String = {
    val builder = new java.lang.StringBuilder()
      .append(prefix)
      .append(".")
      .append(sanitize(name))

    subnames.foreach(subname => builder.append(".").append(sanitize(subname)))

    builder.toString
  }

  def props(host: String,
            port: Int,
            connectionRetryDelay: FiniteDuration,
            bufferSize: Int,
            writeRetryBufferSize: Int,
            metadataMessageInterval: Int,
            metricPrefix: String): Props =
    Props(new GangliaClient(host, port, connectionRetryDelay, bufferSize, writeRetryBufferSize, metadataMessageInterval, metricPrefix))
}
