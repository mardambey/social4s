package facebook4s

object FacebookMarketingApi extends FacebookApiHelpers {

  implicit class FacebookAdsInsightsApi(requestBuilder: FacebookRequestBuilder) {
    def adInsights(
      adId: String,
      metric: String = "",
      period: Option[String] = None,
      since: Option[Long] = None,
      until: Option[Long] = None,
      accessToken: Option[AccessToken] = None): FacebookRequestBuilder = {

      val modifiers = buildModifiers(
        "period" -> period,
        "since" -> since,
        "until" -> until)

      requestBuilder.get(s"$adId/insights/$metric", modifiers, accessToken)
    }

    def campaignInsights(campaignId: String, accessToken: Option[AccessToken] = None) = requestBuilder.get(s"$campaignId/insights", Map.empty, accessToken)

    def adSetInsights(adSetId: String, accessToken: Option[AccessToken] = None) = requestBuilder.get(s"$adSetId/insights", Map.empty, accessToken)
  }
}

