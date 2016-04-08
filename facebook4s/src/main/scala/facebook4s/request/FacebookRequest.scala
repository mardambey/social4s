package facebook4s.request

import facebook4s.api.AccessToken
import facebook4s.response._
import http.client.method.{ PostMethod, GetMethod, HttpMethod }
import http.client.request._
import http.client.response.BatchResponsePart
import play.api.libs.json._

object TimeRangeCompletionEvaluation extends CompletionEvaluation {
  override def apply(request: Request, response: BatchResponsePart): Boolean = {
    request.asInstanceOf[FacebookTimeRangedRequest].currentUntil.exists(_ >= request.asInstanceOf[FacebookTimeRangedRequest].until)
  }
}

case class Value(end_time: String)
case class Datum(values: Seq[Value])
case class Data(data: Seq[Datum])

object Value {
  implicit val fmt = Json.format[Value]
}
object Datum {
  implicit val fmt = Json.format[Datum]
}
object Data {
  implicit val fmt = Json.format[Data]
}

trait ConditionChecker[T] {
  def apply(e1: T, e2: T): Boolean
}

trait JsonExtractor[T] {
  def apply(json: JsValue, checker: ConditionChecker[T]): (T ⇒ Boolean)
}

class TrueChecker[T]() extends ConditionChecker[T] {
  override def apply(ignored: T, ignored2: T): Boolean = true
}

object JsonConditions {

  object data$values$end_time extends JsonExtractor[String] {

    def apply(jsonResponseBody: JsValue, checker: ConditionChecker[String]): (String ⇒ Boolean) = {
      jsonResponseBody
        .validate[Data]
        .asOpt
        .flatMap { d ⇒
          d.data
            .lastOption
            .flatMap(_.values.lastOption)
            .map(lastValue ⇒ checker.apply(lastValue.end_time, _: String))
        }.getOrElse(new TrueChecker[String].apply("", _)) // true if we failed to find what we're looking for
    }
  }
}

class JsonConditionCompletionEvaluation[T](jsonExtractor: JsonExtractor[T], checker: ConditionChecker[T], completionConditionValue: T) extends CompletionEvaluation {
  override def apply(request: Request, response: BatchResponsePart): Boolean =
    jsonExtractor.apply(response.bodyJson, checker).apply(completionConditionValue)
}

///// facebook api

object FacebookEmptyNextPageCompletionEvaluation extends CompletionEvaluation {
  override def apply(request: Request, response: BatchResponsePart): Boolean = {
    val paging = (response.bodyJson \ "paging").validate[FacebookCursorPaging].get
    paging.next.isEmpty
  }
}

object FacebookRequest {

  def queryStringAsStringWithToken(queryString: Map[String, Seq[String]], accessToken: Option[AccessToken]) =
    (queryString ++ accessToken.map(accessTokenQS))
      .flatMap { keyAndValues ⇒
        val key = keyAndValues._1
        keyAndValues._2.map(value ⇒ s"$key=$value").toList
      }
      .mkString("&")

  def accessTokenQS(accessToken: AccessToken): (String, Seq[String]) =
    FacebookBatchRequestBuilder.ACCESS_TOKEN -> Seq(accessToken.token)

  def maybeQueryString(queryString: Map[String, Seq[String]], accessToken: Option[AccessToken]): String = {
    if (queryString.nonEmpty) "?" + queryStringAsStringWithToken(queryString, accessToken)
    else ""
  }
}

case class FacebookGetRequest(relativeUrl: String, headers: Seq[(String, String)], queryString: Map[String, Seq[String]], accessToken: Option[AccessToken], method: HttpMethod = GetMethod)
    extends GetRequest(relativeUrl, headers, queryString, method) {
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = {
    JsObject(Seq(
      "method" -> JsString(method.name),
      "relative_url" -> JsString(relativeUrl + FacebookRequest.maybeQueryString(queryString ++ extraQueryStringParams, accessToken)))).toString()
  }
}

case class FacebookPostRequest(override val relativeUrl: String, override val headers: Seq[(String, String)], override val queryString: Map[String, Seq[String]], data: Option[AccessToken], override val body: Option[String], override val method: HttpMethod = PostMethod)
    extends PostRequest[String](relativeUrl, headers, queryString, body, method) {
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = {
    JsObject(Seq(
      "method" -> JsString(method.name),
      "relative_url" -> JsString(relativeUrl + FacebookRequest.maybeQueryString(queryString ++ extraQueryStringParams, data))) ++
      body.map(b ⇒ Seq("body" -> JsString(b))).getOrElse(Seq.empty) // TODO: URLEncode() the body string, needed?
      ).toString()
  }
}

trait FacebookPaginatedRequest extends Request {
  def originalRequest: Request
  def nextRequest(responsePart: BatchResponsePart): FacebookPaginatedRequest
}

case class FacebookTimeRangedRequest(since: Long, until: Long, request: Request, currentSince: Option[Long] = None, currentUntil: Option[Long] = None)
    extends FacebookPaginatedRequest {
  protected lazy val sinceUntil = Map("since" -> Seq(currentSince.getOrElse(since).toString), "until" -> Seq(currentUntil.getOrElse(until).toString))
  override val method = request.method
  override val headers = request.headers
  override val relativeUrl = request.relativeUrl
  override val queryString = request.queryString ++ sinceUntil
  //override val data = request.data
  override def originalRequest = copy(currentSince = None, currentUntil = None)
  override val completionEvaluator = TimeRangeCompletionEvaluation
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = request.toJson(extraQueryStringParams ++ sinceUntil)
  override def nextRequest(responsePart: BatchResponsePart): FacebookTimeRangedRequest = {
    val paging = (responsePart.bodyJson \ "paging").validate[FacebookTimePaging].get
    copy(currentSince = paging.nextSinceLong, currentUntil = paging.nextUntilLong)
  }
}

case class FacebookCursorPaginatedRequest(request: Request, paging: Option[FacebookCursorPaging] = None, completionEvaluator: CompletionEvaluation = FacebookEmptyNextPageCompletionEvaluation)
    extends FacebookPaginatedRequest {
  protected lazy val after = paging.map(p ⇒ Map("after" -> Seq(p.cursors.after))).getOrElse(Map.empty)
  override val method = request.method
  override val headers = request.headers
  override val relativeUrl = request.relativeUrl
  override val queryString = request.queryString ++ after
  //override val data = request.data
  override def originalRequest = copy(paging = None)
  override def toJson(extraQueryStringParams: Map[String, Seq[String]] = Map.empty): String = request.toJson(extraQueryStringParams ++ after)
  override def nextRequest(responsePart: BatchResponsePart): FacebookCursorPaginatedRequest = {
    val paging = (responsePart.bodyJson \ "paging").validate[FacebookCursorPaging].get
    copy(paging = Some(paging))
  }
}

