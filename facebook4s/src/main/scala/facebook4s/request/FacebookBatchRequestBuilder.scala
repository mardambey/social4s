package facebook4s.request

import java.nio.ByteBuffer

import com.ning.http.client.FluentCaseInsensitiveStringsMap
import com.ning.http.client.multipart.{ByteArrayPart, MultipartUtils}
import facebook4s.api.AccessToken
import facebook4s.connection.FacebookConnectionInformation
import facebook4s.response.{FacebookBatchResponse, FacebookBatchResponsePart}
import http.client.connection.HttpConnection
import http.client.method.{HttpMethod, PostMethod}
import http.client.request.{HttpBatchRequestBuilder, Request, TrueCompletionEvaluation}
import http.client.response.HttpResponse

import scala.collection.mutable.ListBuffer

object FacebookBatchRequestBuilder {

  val ACCESS_TOKEN = "access_token"
  val FB_BATCH_PATH = ""
  val BATCH = "batch"

  def http(protocol: String, domain: String, version: String, path: String): String =
    s"$protocol://$domain/$version/$path"

  def accessTokenQS(accessToken: AccessToken): (String, Seq[String]) =
    ACCESS_TOKEN → Seq(accessToken.token)
}

import FacebookBatchRequestBuilder._

class FacebookBatchRequestBuilder(cfg: FacebookConnectionInformation, connection: HttpConnection, accessToken: Option[AccessToken], requests: ListBuffer[Request] = ListBuffer.empty)
    extends HttpBatchRequestBuilder[FacebookBatchResponse, FacebookBatchResponsePart, FacebookBatchRequestBuilder](requests, connection, http(cfg.protocol, cfg.graphApiHost, cfg.version, FB_BATCH_PATH)) {

  private val boundary: String =
    "------------------------" + scala.util.Random.alphanumeric.take(16).mkString

  override protected def maybeRanged(since: Option[Long], until: Option[Long], request: Request): Request =
    if (since.isDefined && until.isDefined) FacebookTimeRangedRequest(since.get, until.get, request)
    else request

  override protected def maybePaginated(paginated: Boolean, request: Request): Request =
    if (paginated) FacebookCursorPaginatedRequest(request)
    else request

  override protected def accumulateCompleteRequest(reqRes: (Request, FacebookBatchResponsePart)): (Request, FacebookBatchResponsePart) = reqRes match {
    case (req: FacebookPaginatedRequest, res) ⇒ (req.originalRequest, res) // original request so we can group all parts on it later
    case rr                                   ⇒ rr
  }

  override protected def newRequestFromIncompleteRequest(reqRes: (Request, FacebookBatchResponsePart)): Request = {
    reqRes._1.asInstanceOf[FacebookPaginatedRequest].nextRequest(reqRes._2)
  }

  override protected def makeBatchRequestBody(requests: Seq[Request]): Array[Byte] = {
    val parts = accessToken.map { a ⇒ Seq(ACCESS_TOKEN → a.token.getBytes("utf-8")) }
      .getOrElse(Seq.empty[(String, Array[Byte])]) ++
      Seq(BATCH → ("[" + requests.map(_.toJson()).mkString(",") + "]").getBytes("utf-8"))

    val s = accessToken.map { a ⇒ Seq(ACCESS_TOKEN → a.token) }
      .getOrElse(Seq.empty[(String, String)]) ++
      Seq(BATCH → ("[" + requests.map(_.toJson()).mkString(",") + "]"))

    val byteArrayParts = parts.map(p ⇒ new ByteArrayPart(p._1, p._2))
    val headers = new FluentCaseInsensitiveStringsMap().add("Content-Type", s"multipart/form-data; boundary=$boundary")

    val request = MultipartUtils.newMultipartBody(java.util.Arrays.asList(byteArrayParts: _*), headers)
    val buf = ByteBuffer.allocate(request.getContentLength.toInt)
    request.read(buf)

    buf.array()
  }

  override protected def makeBatchRequest(batchUrl: String, _body: Array[Byte]): Request = {
    val _headers = Seq(("Content-Type", s"multipart/form-data; boundary=$boundary"))
    new Request {
      val completionEvaluator = new TrueCompletionEvaluation
      val method = PostMethod
      val queryString = Map.empty[String, Seq[String]]
      val body = Some(_body)
      val headers = _headers
      val relativeUrl = batchUrl
      def toJson(extraQueryStringParams: Map[String, Seq[String]]): String = ""
    }
  }

  override protected def fromHttpResponse(wsResponse: HttpResponse): FacebookBatchResponse = {
    FacebookBatchResponse(wsResponse.status, wsResponse.headers, wsResponse.json.validate[Seq[FacebookBatchResponsePart]].getOrElse(Seq.empty))
  }
}