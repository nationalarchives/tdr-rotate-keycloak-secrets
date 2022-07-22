package uk.gov.nationalarchives.rotate

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.{ok, post, urlEqualTo}
import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentCaptor, MockitoSugar}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{GetParameterRequest, GetParameterResponse, Parameter}

import java.util.Base64
import scala.jdk.CollectionConverters.ListHasAsScala

class KeycloakAdminClientSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  "getting the admin client" should "get the secret parameter" in {
    val ssmMock = mock[SsmClient]
    val getParameterCaptor: ArgumentCaptor[GetParameterRequest] = ArgumentCaptor.forClass(classOf[GetParameterRequest])
    val parameter = Parameter.builder.value("testsecret").build
    val getParameterResponse = GetParameterResponse.builder.parameter(parameter).build
    when(ssmMock.getParameter(getParameterCaptor.capture()))
      .thenReturn(getParameterResponse)

    new KeycloakAdminClient(ssmMock)

    verify(ssmMock).getParameter(getParameterCaptor.capture())
  }

  "using the admin client" should "call the token endpoint with the correct value" in {
    val authServer = new WireMockServer(8000)
    authServer
      .stubFor(post(urlEqualTo("/realms/tdr/protocol/openid-connect/token"))
        .willReturn(ok(s"""{"access_token": "a"}""").withHeader("Content-Type", "application/json")))
    authServer.start()
    val ssmMock = mock[SsmClient]
    val secret = "testsecret"
    val parameter = Parameter.builder.value(secret).build

    val getParameterResponse = GetParameterResponse.builder.parameter(parameter).build
    when(ssmMock.getParameter(any[GetParameterRequest]))
      .thenReturn(getParameterResponse)

    new KeycloakAdminClient(ssmMock).client.tokenManager().getAccessTokenString

    val authHeader = authServer.getAllServeEvents.asScala.head
      .getRequest.header("Authorization")
      .values().asScala.head.stripPrefix("Basic ")
    val credentials = Base64.getDecoder.decode(authHeader).map(_.toChar).mkString

    credentials should equal(s"test-client:$secret")
    authServer.stop()
  }
}
