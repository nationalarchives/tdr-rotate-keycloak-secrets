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
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.{PutSecretValueRequest, PutSecretValueResponse}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{PutParameterRequest, PutParameterResponse}
import uk.gov.nationalarchives.rotate.RotateClientSecrets.clients

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

  "The rotate function" should "call the secrets manager service for ayr client" in {
    val ssmClients: Map[String, String] = Map("ayr" -> "b")
    stubAuthServer(List(AuthServerClients("ayr", stubClientSecretPost = true)))

    val mockSecretsManager = Mockito.mock(classOf[SecretsManagerClient])
    val putSecretValueResponse = PutSecretValueResponse.builder.build()
    when(mockSecretsManager.putSecretValue(any[PutSecretValueRequest])).thenReturn(putSecretValueResponse)

    val mockEcsClient = mock[EcsClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mock[SsmClient], mockEcsClient, mockSecretsManager, stage, ssmClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.head.message should be("Client ayr has been rotated successfully")
  }


  "The rotate function" should "call the ssm service for all clients except ayr" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockEcsClient = mock[EcsClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mockEcsClient, mock[SecretsManagerClient], stage, ssmClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.head.message should be("Client a has been rotated successfully")
  }

  "The rotate function" should "use the correct secret path" in {
    val parameterPath = "/test/parameter/path"
    val secretId = "secretId"
    val ssmClients: Map[String, String] = Map("a" -> parameterPath, "ayr" -> secretId)
    stubAuthServer(List(
      AuthServerClients("a", stubClientSecretPost = true),
      AuthServerClients("ayr", stubClientSecretPost = true)
    ))

    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    val putParameterArgumentCaptor: ArgumentCaptor[PutParameterRequest] = ArgumentCaptor.forClass(classOf[PutParameterRequest])
    when(mockSsm.putParameter(putParameterArgumentCaptor.capture())).thenReturn(putParameterResponse)

    val mockSecretsManager = Mockito.mock(classOf[SecretsManagerClient])
    val putSecretValueResponse = PutSecretValueResponse.builder.build()
    val putSecretValueArgumentCaptor: ArgumentCaptor[PutSecretValueRequest] = ArgumentCaptor.forClass(classOf[PutSecretValueRequest])
    when(mockSecretsManager.putSecretValue(putSecretValueArgumentCaptor.capture())).thenReturn(putSecretValueResponse)

    val mockEcsClient = mock[EcsClient]
    val updateServiceResponse = UpdateServiceResponse.builder().build()
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenReturn(updateServiceResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mockEcsClient, mockSecretsManager, stage, ssmClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(2)
    results.exists(_.message == "Client a has been rotated successfully") should be(true)
    results.exists(_.message == "Client ayr has been rotated successfully") should be(true)
    putParameterArgumentCaptor.getValue.name() should equal(parameterPath)
    putSecretValueArgumentCaptor.getValue.secretId() should equal(secretId)
  }

  "The rotate function" should "return an error and success response if only one update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "/a/path", "b" -> "/b/path")
    val authServerClients = List(
      AuthServerClients("a", stubClientSecretPost = true),
      AuthServerClients("b", stubClientSecretPost = false)
    )
    stubAuthServer(authServerClients)
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockSecretsManager = Mockito.mock(classOf[SecretsManagerClient])
    val putSecretValueResponse = PutSecretValueResponse.builder.build()
    when(mockSecretsManager.putSecretValue(any[PutSecretValueRequest])).thenReturn(putSecretValueResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mock[EcsClient], mockSecretsManager, stage, ssmClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(2)
    results.exists(_.message == "Client a has been rotated successfully") should be(true)
    results.exists(_.message == "Client b has failed HTTP 404 Not Found") should be(true)
  }

  "The rotate function" should "return an error if the Keycloak secret update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "/a/path")
    val authServerClients = List(AuthServerClients("a", stubClientSecretPost = false))
    stubAuthServer(authServerClients)

    val mockSecretsManager = Mockito.mock(classOf[SecretsManagerClient])
    val putSecretValueResponse = PutSecretValueResponse.builder.build()
    when(mockSecretsManager.putSecretValue(any[PutSecretValueRequest])).thenReturn(putSecretValueResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, Mockito.mock(classOf[SsmClient]), mock[EcsClient], mockSecretsManager, stage, ssmClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(1)

    results.exists(_.message == "Client a has failed HTTP 404 Not Found") should be(true)
  }

  "The rotate function" should "return an error if the systems manager update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val errorMessage = "Error putting parameter"
    when(mockSsm.putParameter(any[PutParameterRequest])).thenThrow(new Exception(errorMessage))

    val mockSecretsManager = Mockito.mock(classOf[SecretsManagerClient])
    val putSecretValueResponse = PutSecretValueResponse.builder.build()
    when(mockSecretsManager.putSecretValue(any[PutSecretValueRequest])).thenReturn(putSecretValueResponse)

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mock[EcsClient], mockSecretsManager, stage, ssmClients)
    val results = rotateClientSecrets.rotate()

    results.size should be(1)
    results.exists(_.message == s"Client a has failed $errorMessage") should be(true)
  }

  "The rotate function" should "return an error if the ECS service update fails" in {
    val ssmClients: Map[String, String] = Map("a" -> "b")
    stubAuthServer(List(AuthServerClients("a", stubClientSecretPost = true)))
    val mockSsm = Mockito.mock(classOf[SsmClient])
    val putParameterResponse = PutParameterResponse.builder.build()
    when(mockSsm.putParameter(any[PutParameterRequest])).thenReturn(putParameterResponse)

    val mockSecretsManager = Mockito.mock(classOf[SecretsManagerClient])
    val putSecretValueResponse = PutSecretValueResponse.builder.build()
    when(mockSecretsManager.putSecretValue(any[PutSecretValueRequest])).thenReturn(putSecretValueResponse)

    val mockEcsClient = mock[EcsClient]
    val errorMessage = "ECS Update Service Failed"
    when(mockEcsClient.updateService(any[UpdateServiceRequest]))
      .thenThrow(new Exception(errorMessage))

    val rotateClientSecrets = new RotateClientSecrets(client, mockSsm, mockEcsClient, mockSecretsManager, stage, ssmClients)
    val results = rotateClientSecrets.rotate()
    results.size should be(1)
    results.exists(_.message == s"Client a has failed $errorMessage") should be(true)
  }

  "The clients" should "be correct" in {
    val environment = "test"
    clients("tdr") should equal(s"/$environment/keycloak/client/secret")
    clients("tdr-backend-checks") should equal(s"/$environment/keycloak/backend_checks_client/secret")
    clients("tdr-realm-admin") should equal(s"/$environment/keycloak/realm_admin_client/secret")
    clients("tdr-reporting") should equal (s"/$environment/keycloak/reporting_client/secret")
    clients("tdr-rotate-secrets") should equal (s"/$environment/keycloak/rotate_secrets_client/secret")
    clients("tdr-user-admin") should be(s"/$environment/keycloak/user_admin_client/secret")
    clients("tdr-rotate-secrets") should be (s"/$environment/keycloak/rotate_secrets_client/secret")
    clients("ayr") should equal(s"ayr-client-secret-arn")
  }
}
