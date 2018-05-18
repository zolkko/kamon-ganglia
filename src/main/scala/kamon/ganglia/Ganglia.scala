package kamon.ganglia

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import com.typesafe.config.Config
import info.ganglia.gmetric4j.gmetric.{GMetricSlope, GMetricType}
import info.ganglia.gmetric4j.xdr.v31x._
import kamon.{Kamon, MetricReporter}
import kamon.metric._
import org.slf4j.LoggerFactory
import org.acplt.oncrpc.XdrBufferEncodingStream


class GangliaMetricReporter extends MetricReporter {

  @volatile
  private var settings = GangliaMetricReporter.readSettings(Kamon.config())

  override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
    val s = settings
    val client = GangliaClient(s.hostname, s.port, s.failureRetryDelay, s.bufferSize, s.retryBufferSize,
      s.metadataMessageInterval, s.metricPrefix)
    try {
      client.sendSnapshot(snapshot)
    } finally {
      client.close()
    }
  }

  override def start(): Unit = {
  }

  override def stop(): Unit = {
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
                    metricPrefix: String) extends AutoCloseable {

  import GangliaClient._
  import java.net.DatagramPacket
  import java.net.DatagramSocket
  import java.net.InetAddress

  private val TMAX = 60
  private val DMAX = 0

  private val log = LoggerFactory.getLogger(classOf[GangliaClient])

  private val xdr = new XdrBufferEncodingStream(bufferSize)
  private val metricCounterMap = new java.util.HashMap[String, Integer]()

  private val address = InetAddress.getByName(host)
  private val localHostName = InetAddress.getLocalHost.getHostName

  private val socket = new DatagramSocket

  def sendSnapshot(snapshot: PeriodSnapshot): Unit = {
    val messages = dispatchMetricValue(snapshot.metrics.gauges) ++
      dispatchMetricValue(snapshot.metrics.counters) ++
      dispatchHistograms(snapshot.metrics.histograms) ++
      dispatchMinMaxCounters(snapshot.metrics.rangeSamplers)

    sendUdp(messages)
  }

  private def dispatchMetricValue(gauges: Seq[MetricValue]): Seq[Array[Byte]] = gauges flatMap { gauge =>
    val group = genName(metricPrefix, gauge.name)
    announce(genName(metricPrefix, gauge.name, "value"), group, gauge.value, gauge.unit)
  }

  private def dispatchHistograms(histograms: Seq[MetricDistribution]): Seq[Array[Byte]] = histograms flatMap { hist =>
    val group = genName(metricPrefix, hist.name)

    announce(genName(metricPrefix, hist.name, "count"), group, hist.distribution.count, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "min"), group, hist.distribution.min, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "max"), group, hist.distribution.max, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "p50"), group, hist.distribution.percentile(50d).value, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "p90"), group, hist.distribution.percentile(90d).value, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "p99"), group, hist.distribution.percentile(99d).value, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "sum"), group, hist.distribution.sum, hist.unit)
  }

  private def dispatchMinMaxCounters(histograms: Seq[MetricDistribution]): Seq[Array[Byte]] = histograms flatMap { hist =>
    val group = genName(metricPrefix, hist.name)

    announce(genName(metricPrefix, hist.name, "min"), group, hist.distribution.min, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "max"), group, hist.distribution.max, hist.unit) ++
      announce(genName(metricPrefix, hist.name, "avg"), group, hist.distribution.sum / hist.distribution.count, hist.unit)
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

  private def announce(name: String, groupName: String, value: Long, units: MeasurementUnit): Seq[Array[Byte]] = {
    val metric_id = new Ganglia_metric_id
    metric_id.spoof = false
    metric_id.host = localHostName
    metric_id.name = name

    if (isTimeToSendMetadata(name)) {
      encodeGMetric(metric_id, name, groupName, value.toString, GMetricType.DOUBLE, units.magnitude.name, GMetricSlope.BOTH, TMAX, DMAX)

      val metaInfo: Array[Byte] = Array.fill(xdr.getXdrLength)(0)
      System.arraycopy(xdr.getXdrData, 0, metaInfo, 0, xdr.getXdrLength)

      encodeGValue(metric_id, value.toString)

      val data: Array[Byte] = Array.fill(xdr.getXdrLength)(0)
      System.arraycopy(xdr.getXdrData, 0, data, 0, xdr.getXdrLength)

      Seq(metaInfo, data)
    } else {
      encodeGValue(metric_id, value.toString)

      val data: Array[Byte] = Array.fill(xdr.getXdrLength)(0)
      System.arraycopy(xdr.getXdrData, 0, data, 0, xdr.getXdrLength)

      Seq(data)
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

    xdr.beginEncoding(address, port)
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

    xdr.beginEncoding(address, port)
    value_msg.xdrEncode(xdr)
    xdr.endEncoding()
  }

  private def sendUdp(messages: Seq[Array[Byte]]): Unit = {
    try {
      for (msg <- messages) {
        val packet = new DatagramPacket(msg, msg.length, address, port)
        socket.send(packet)
      }
    } catch {
      case cause: Throwable =>
        log.error("Failed to send metrics snapshot to Ganglia: {}", cause)
    }
  }

  override def close(): Unit = {
    if (socket ne null) {
      socket.close()
    }
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

  def apply(host: String,
            port: Int,
            connectionRetryDelay: FiniteDuration,
            bufferSize: Int,
            writeRetryBufferSize: Int,
            metadataMessageInterval: Int,
            metricPrefix: String): GangliaClient =
    new GangliaClient(host, port, connectionRetryDelay, bufferSize, writeRetryBufferSize, metadataMessageInterval, metricPrefix)
}
