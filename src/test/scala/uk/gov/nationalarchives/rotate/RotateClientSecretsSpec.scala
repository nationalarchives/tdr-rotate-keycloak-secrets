package uk.gov.nationalarchives.rotate

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.{get, ok, post, urlEqualTo}
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.keycloak.admin.client.{Keycloak, KeycloakBuilder}
import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentCaptor, Mockito, MockitoSugar}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{UpdateServiceRequest, UpdateServiceResponse}
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.{UpdateConnectionRequest, UpdateConnectionResponse}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{PutParameterRequest, PutParameterResponse}
import uk.gov.nationalarchives.rotate.MessageSender.Message
import uk.gov.nationalarchives.rotate.RotateClientSecrets.{ApiConnectionClient, apiConnectionClients, clients}

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
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.head.message should be("Client a secret has been rotated successfully")
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(0)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "update eventbridge connections when the client is used by connections" in {
    val ssmClients: Map[String, String] = Map("a" -> "c")
    val apiConnectionClients = Set(ApiConnectionClient("a", "aConnectionArn"))
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    val updateConnectionResponse = UpdateConnectionResponse.builder()
      .connectionArn("aConnectionArn")
      .build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)
    when(mockEventBridgeClient.updateConnection(any[UpdateConnectionRequest])).thenReturn(updateConnectionResponse)

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(2)
    results.head.message should be("Client a secret has been rotated successfully")
    results.last.message should be("EventBridge connections secrets using a updated")
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(1)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "use the correct secret path" in {
    val parameterPath = "/test/parameter/path"
    val ssmClients: Map[String, String] = Map("a" -> parameterPath)
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))

    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    val putParameterArgumentCaptor: ArgumentCaptor[PutParameterRequest] = ArgumentCaptor.forClass(classOf[PutParameterRequest])
    when(mockSsm.putParameter(putParameterArgumentCaptor.capture())).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.head.message should be("Client a secret has been rotated successfully")
    putParameterArgumentCaptor.getValue.name() should equal(parameterPath)
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(0)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "return an error and success response if only one secret update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "/a/path", "b" -> "/b/path")
    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    val authServerClients = List(
      AuthServerClients("a", stubClientSecretPost = true),
      AuthServerClients("b", stubClientSecretPost = false)
    )
    stubAuthServer(authServerClients)
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(2)
    results.exists(_.message == "Client a secret has been rotated successfully") should be(true)
    results.exists(_.message == "Client b has failed HTTP 404 Not Found") should be(true)
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(0)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "return an error if the Keycloak secret update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "/a/path")
    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    val authServerClients = List(AuthServerClients("a", stubClientSecretPost = false))
    stubAuthServer(authServerClients)

    val rotateClientSecrets = new RotateClientSecrets(
      client, Mockito.mock(classOf[SsmClient]), mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(1)

    results.exists(_.message == "Client a has failed HTTP 404 Not Found") should be(true)
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(0)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "return an error if the systems manager update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val errorMessage = "Error putting parameter"
    when(mockSsm.putParameter(any[PutParameterRequest])).thenThrow(new Exception(errorMessage))

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(1)
    results.exists(_.message == s"Client a has failed $errorMessage") should be(true)
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(0)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "return an error if the ECS service update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    val errorMessage = "ECS Update Service Failed"
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenThrow(new Exception(errorMessage))

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(2)
    results.contains(Message(s"Client a secret has been rotated successfully")) should be(true)
    results.contains(Message(s"ECS task failed to restart: $errorMessage")) should be(true)
    verify(mockEventBridgeClient, times(0)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "not update EventBridge connection when its connection client secret fails to rotate" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    val apiConnectionClients = Set(ApiConnectionClient("a", "aConnectionArn"))
    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val errorMessage = "Error putting parameter"
    when(mockSsm.putParameter(any[PutParameterRequest])).thenThrow(new Exception(errorMessage))

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(1)
    results.exists(_.message == s"Client a has failed $errorMessage") should be(true)
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(0)).updateConnection(any[UpdateConnectionRequest])
  }

  "The rotate function" should "error when EventBridge fails to update connection client secret but rotate all client secrets successfully" in {
    val ssmClients: Map[String, String] = Map(
      "a" -> "b",
      "c" -> "d")
    val apiConnectionClients = Set(ApiConnectionClient("c", "aConnectionArn"))
    stubAuthServer(List(
      AuthServerClients("a", stubClientSecretPost = true),
      AuthServerClients("c", stubClientSecretPost = true)
    ))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val mockEventBridgeClient = mock[EventBridgeClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    val errorMessage = "EventBridge update error message"
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)
    when(mockEventBridgeClient.updateConnection(any[UpdateConnectionRequest])).thenThrow(new Exception(errorMessage))

    val rotateClientSecrets = new RotateClientSecrets(
      client, mockSsm, mockEcsClient, mockEventBridgeClient, stage, ssmClients, apiConnectionClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(3)
    results.contains(Message("Client c secret has been rotated successfully")) should be(true)
    results.contains(Message(s"EventBridge connections secrets updating has failed: $errorMessage")) should be(true)
    results.contains(Message(s"Client a secret has been rotated successfully")) should be(true)
    verify(mockEcsClient, times(2)).updateService(any[UpdateServiceRequest])
    verify(mockEventBridgeClient, times(1)).updateConnection(any[UpdateConnectionRequest])
  }

  "The clients" should "be correct" in {
    val environment = "test"
    clients("tdr") should equal(s"/$environment/keycloak/client/secret")
    clients("tdr-backend-checks") should equal(s"/$environment/keycloak/backend_checks_client/secret")
    clients("tdr-realm-admin") should equal(s"/$environment/keycloak/realm_admin_client/secret")
    clients("tdr-reporting") should equal (s"/$environment/keycloak/reporting_client/secret")
    clients("tdr-rotate-secrets") should equal (s"/$environment/keycloak/rotate_secrets_client/secret")
    clients("tdr-transfer-service") should be (s"/$environment/keycloak/transfer_service_client/secret")
    clients("tdr-user-admin") should be(s"/$environment/keycloak/user_admin_client/secret")
    clients("tdr-user-read") should be (s"/$environment/keycloak/user_read_client/secret")
    clients("tdr-draft-metadata") should be (s"/$environment/keycloak/draft_metadata_client/secret")
  }

  "The apiConnectionClients" should "be correct" in {
    apiConnectionClients(ApiConnectionClient("tdr-draft-metadata", "connectionName")) shouldBe true
  }
}
