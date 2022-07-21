package uk.gov.nationalarchives.rotate

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.{get, ok, post, urlEqualTo}
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.keycloak.admin.client.{Keycloak, KeycloakBuilder}
import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentCaptor, MockitoSugar}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{UpdateServiceRequest, UpdateServiceResponse}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{PutParameterRequest, PutParameterResponse}

import java.util.UUID

class RotateClientSecretsSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterEach with BeforeAndAfterAll {
  case class AuthServerClients(clientId: String, stubClientSecretPost: Boolean)
  val stage = "test"
  val authServer = new WireMockServer(1080)

  def stubRequest(builder: UrlPattern => MappingBuilder, url: String, responseJson: String): StubMapping = {
    authServer
      .stubFor(builder(urlEqualTo(s"/admin/realms/tdr/clients$url"))
        .willReturn(ok(responseJson)
          .withHeader("Content-Type", "application/json"))
      )
  }

  def stubAuthServer(clients: List[AuthServerClients]): Unit = {
    clients.foreach(c => {
      val uuid = UUID.randomUUID()
      stubRequest(get, s"?clientId=${c.clientId}", s"""[{"id": "$uuid","clientId": "tdr"}]""")
      if (c.stubClientSecretPost) {
        stubRequest(post, s"/$uuid/client-secret", """{"value": "secret"}""")
      }
    })
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    authServer.start()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    authServer.resetAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    authServer.stop()
  }

  val client: Keycloak = KeycloakBuilder.builder()
    .serverUrl("http://localhost:1080")
    .realm("tdr")
    .authorization("token")
    .build()


  "The rotate function" should "call the correct services" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = mock[SsmClient]
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mockEcsClient, stage, ssmClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.head.success should be(true)
  }

  "The rotate function" should "use the correct secret path" in {
    val parameterPath = "/test/parameter/path"
    val ssmClients: Map[String, String] = Map("a" -> parameterPath)
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))

    val mockSsm = mock[SsmClient]
    val putParameterResponse = PutParameterResponse.builder.build()
    val putParameterArgumentCaptor: ArgumentCaptor[PutParameterRequest] = ArgumentCaptor.forClass(classOf[PutParameterRequest])
    when(mockSsm.putParameter(putParameterArgumentCaptor.capture())).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mockEcsClient, stage, ssmClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.head.success should be(true)
    putParameterArgumentCaptor.getValue.name() should equal(parameterPath)
  }

  "The rotate function" should "return an error and success response if only one update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "/a/path", "b" -> "/b/path")
    val authServerClients = List(
      AuthServerClients("a", stubClientSecretPost = true),
      AuthServerClients("b", stubClientSecretPost = false)
    )
    stubAuthServer(authServerClients)
    val mockSsm = mock[SsmClient]
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mock[EcsClient], stage, ssmClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(2)
    results.exists(_.success) should be(true)
    results.exists(!_.success) should be(true)
    val resultErrorMessage = results.find(!_.success).get.rotationResultErrorMessage.get
    resultErrorMessage should equal("HTTP 404 Not Found")
  }

  "The rotate function" should "return an error if the Keycloak secret update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "/a/path")
    val authServerClients = List(AuthServerClients("a", stubClientSecretPost = false))
    stubAuthServer(authServerClients)

    val rotateClientSecrets = new RotateClientSecrets(client, mock[SsmClient], mock[EcsClient], stage, ssmClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(1)

    val resultErrorMessage = results.find(!_.success).get.rotationResultErrorMessage.get
    resultErrorMessage should equal("HTTP 404 Not Found")
  }

  "The rotate function" should "return an error if the systems manager update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = mock[SsmClient]
    val errorMessage = "Error putting parameter"
    when(mockSsm.putParameter(any[PutParameterRequest])).thenThrow(new Exception(errorMessage))

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mock[EcsClient], stage, ssmClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(1)
    results.head.success should be(false)
    results.head.rotationResultErrorMessage.get should equal(errorMessage)
  }

  "The rotate function" should "return an error if the ECS service update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = mock[SsmClient]
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val errorMessage = "ECS Update Service Failed"
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenThrow(new Exception(errorMessage))

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mockEcsClient, stage, ssmClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.head.success should be(false)
    results.head.rotationResultErrorMessage.get should equal(errorMessage)
  }
}
