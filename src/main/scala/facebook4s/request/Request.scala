package facebook4s.request

import facebook4s.api.AccessToken
import facebook4s.connection.FacebookConnection
import facebook4s.response.{ FacebookCursorPaging, FacebookTimePaging, FacebookBatchResponsePart }
import play.api.libs.json.{ JsObject, JsString }

trait Request {
  val method: HttpMethod
  val relativeUrl: String
  val queryString: Map[String, Seq[String]]
  val accessToken: Option[AccessToken]
  val completionEvaluator: CompletionEvaluation
  def isComplete(response: FacebookBatchResponsePart): Boolean = completionEvaluator(this, response)
  def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String
}

object Request {

  def queryStringAsStringWithToken(queryString: Map[String, Seq[String]], accessToken: Option[AccessToken]) =
    (queryString ++ accessToken.map(accessTokenQS))
      .flatMap { keyAndValues ⇒
        val key = keyAndValues._1
        keyAndValues._2.map(value ⇒ s"$key=$value").toList
      }
      .mkString("&")

  def accessTokenQS(accessToken: AccessToken): (String, Seq[String]) =
    FacebookConnection.ACCESS_TOKEN -> Seq(accessToken.token)

  def maybeQueryString(queryString: Map[String, Seq[String]], accessToken: Option[AccessToken]): String = {
    if (queryString.nonEmpty) "?" + queryStringAsStringWithToken(queryString, accessToken)
    else ""
  }
}

trait PaginatedRequest[R <: Request, PaginationInfo] extends Request {
  def originalRequest: Request
  def nextRequest(responsePart: FacebookBatchResponsePart): PaginatedRequest[R, PaginationInfo]
}

trait HttpMethod { val name: String }
case object GetMethod extends HttpMethod { override val name = "GET" }
case object PostMethod extends HttpMethod { override val name = "POST" }

case class GetRequest(relativeUrl: String, queryString: Map[String, Seq[String]], accessToken: Option[AccessToken], method: HttpMethod = GetMethod) extends Request {
  override val completionEvaluator = new TrueCompletionEvaluation
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = {
    JsObject(Seq(
      "method" -> JsString(method.name),
      "relative_url" -> JsString(relativeUrl + Request.maybeQueryString(queryString ++ extraQueryStringParams, accessToken)))).toString()
  }
}

case class PostRequest(relativeUrl: String, queryString: Map[String, Seq[String]], accessToken: Option[AccessToken], body: Option[String], method: HttpMethod = PostMethod) extends Request {
  override val completionEvaluator = new TrueCompletionEvaluation
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = {
    JsObject(Seq(
      "method" -> JsString(method.name),
      "relative_url" -> JsString(relativeUrl + Request.maybeQueryString(queryString ++ extraQueryStringParams, accessToken))) ++
      body.map(b ⇒ Seq("body" -> JsString(b))).getOrElse(Seq.empty) // TODO: URLEncode() the body string, needed?
      ).toString()
  }
}

case class TimeRangedRequest(since: Long, until: Long, request: Request, currentSince: Option[Long] = None, currentUntil: Option[Long] = None) extends PaginatedRequest[TimeRangedRequest, FacebookTimePaging] {
  protected lazy val sinceUntil = Map("since" -> Seq(currentSince.getOrElse(since).toString), "until" -> Seq(currentUntil.getOrElse(until).toString))
  override val method = request.method
  override val relativeUrl = request.relativeUrl
  override val queryString = request.queryString ++ sinceUntil
  override val accessToken = request.accessToken
  override def originalRequest = copy(currentSince = None, currentUntil = None)
  override val completionEvaluator = TimeRangeCompletionEvaluation
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = request.toJson(extraQueryStringParams ++ sinceUntil)
  override def nextRequest(responsePart: FacebookBatchResponsePart): TimeRangedRequest = {
    val paging = (responsePart.bodyJson \ "paging").validate[FacebookTimePaging].get
    copy(currentSince = paging.nextSinceLong, currentUntil = paging.nextUntilLong)
  }
}

trait CompletionEvaluation extends ((Request, FacebookBatchResponsePart) ⇒ Boolean)

class TrueCompletionEvaluation extends CompletionEvaluation {
  override def apply(request: Request, response: FacebookBatchResponsePart): Boolean = true
}

object TimeRangeCompletionEvaluation extends CompletionEvaluation {
  override def apply(request: Request, response: FacebookBatchResponsePart): Boolean = {
    request.asInstanceOf[TimeRangedRequest].currentUntil.exists(_ >= request.asInstanceOf[TimeRangedRequest].until)
  }
}

//object TimeBoundCompletionEvaluation extends CompletionEvaluation[Request] {
//  override def apply(request: Request, response: FacebookBatchResponsePart): Boolean = {
//    // TODO: implement this
//    ???
//  }
//}

object EmptyNextPageCompletionEvaluation extends CompletionEvaluation {
  override def apply(request: Request, response: FacebookBatchResponsePart): Boolean = {
    val paging = (response.bodyJson \ "paging").validate[FacebookCursorPaging].get
    paging.next.isEmpty
  }
}

case class CursorPaginatedRequest(request: Request, paging: Option[FacebookCursorPaging] = None, completionEvaluator: CompletionEvaluation = EmptyNextPageCompletionEvaluation) extends PaginatedRequest[CursorPaginatedRequest, FacebookCursorPaging] {
  protected lazy val after = paging.map(p ⇒ Map("after" -> Seq(p.cursors.after))).getOrElse(Map.empty)
  override val method = request.method
  override val relativeUrl = request.relativeUrl
  override val queryString = request.queryString ++ after
  override val accessToken = request.accessToken
  override def originalRequest = copy(paging = None)
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = request.toJson(extraQueryStringParams ++ after)
  override def nextRequest(responsePart: FacebookBatchResponsePart): CursorPaginatedRequest = {
    val paging = (responsePart.bodyJson \ "paging").validate[FacebookCursorPaging].get
    copy(paging = Some(paging))
  }
}
