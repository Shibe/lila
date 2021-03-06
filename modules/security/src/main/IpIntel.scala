package lila.security

import play.api.libs.ws.WSClient
import scala.concurrent.duration._

import lila.common.{ EmailAddress, IpAddress }

final class IpIntel(
    ws: WSClient,
    cacheApi: lila.memo.CacheApi,
    contactEmail: EmailAddress
)(implicit ec: scala.concurrent.ExecutionContext) {

  def apply(ip: IpAddress): Fu[Int] = failable(ip).nevermind

  def failable(ip: IpAddress): Fu[Int] =
    if (IpIntel isBlacklisted ip) fuccess(90)
    else if (contactEmail.value.isEmpty) fuccess(0)
    else cache get ip

  private val cache = cacheApi[IpAddress, Int](8192, "ipIntel") {
    _.expireAfterWrite(3 days)
      .buildAsyncFuture { ip =>
        val url = s"https://check.getipintel.net/check.php?ip=$ip&contact=${contactEmail.value}"
        ws.url(url)
          .get()
          .dmap(_.body)
          .flatMap { str =>
            str.toFloatOption.fold[Fu[Int]](fufail(s"Invalid ratio ${str.take(140)}")) { ratio =>
              if (ratio < 0) fufail(s"IpIntel error $ratio on $url")
              else fuccess((ratio * 100).toInt)
            }
          }
          .monSuccess(_.security.proxy.request)
          .addEffect { percent =>
            lila.mon.security.proxy.percent.record(percent max 0)
          }
      }
  }
}

object IpIntel {

  // Proxies ipintel doesn't detect
  private val blackList = List(
    "5.121.",
    "5.122."
  )

  def isBlacklisted(ip: IpAddress): Boolean = blackList.exists(ip.value.startsWith)
}
