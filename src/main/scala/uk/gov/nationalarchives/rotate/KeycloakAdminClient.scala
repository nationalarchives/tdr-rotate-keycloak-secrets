package uk.gov.nationalarchives.rotate

import com.typesafe.config.{Config, ConfigFactory}
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.{Keycloak, KeycloakBuilder}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import uk.gov.nationalarchives.rotate.KeycloakAdminClient.config

import java.net.URI

class KeycloakAdminClient(ssmClient: SsmClient) {

  private val authUrl: String = config.getString("keycloak.url")
  private val userAdminClient: String = config.getString("keycloak.user.admin.client")

  def getClientSecret(secretPath: String): String = {
    val getParameterRequest = GetParameterRequest.builder.name(secretPath).withDecryption(true).build
    ssmClient.getParameter(getParameterRequest).parameter().value()
  }

  val secretPath: String = config.getString("keycloak.user.admin.secret_path")
  val userAdminSecret: String = getClientSecret(secretPath)

  val client: Keycloak = KeycloakBuilder.builder()
    .serverUrl(s"$authUrl")
    .realm("tdr")
    .clientId(userAdminClient)
    .clientSecret(userAdminSecret)
    .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
    .build()
}
object KeycloakAdminClient {
  val config: Config = ConfigFactory.load()
  val endpoint: String = config.getString("ssm.endpoint")

  val ssmClient: SsmClient = SsmClient.builder()
    .endpointOverride(URI.create(endpoint))
    .region(Region.EU_WEST_2)
    .build()
  def apply() = new KeycloakAdminClient(ssmClient)
}
