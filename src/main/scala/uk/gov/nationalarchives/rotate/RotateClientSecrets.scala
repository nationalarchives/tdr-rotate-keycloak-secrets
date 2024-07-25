package uk.gov.nationalarchives.rotate

import org.keycloak.admin.client.Keycloak
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{UpdateServiceRequest, UpdateServiceResponse}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{ParameterType, PutParameterRequest}
import uk.gov.nationalarchives.rotate.ApplicationConfig._
import uk.gov.nationalarchives.rotate.MessageSender.Message

import scala.util.{Failure, Success, Try}

class RotateClientSecrets(keycloakClient: Keycloak,
                          ssmClient: SsmClient,
                          ecsClient: EcsClient,
                          stage: String,
                          clients: Map[String, String]) {

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)

  private def restartFrontEndService(): UpdateServiceResponse = {
    val updateServiceRequest = UpdateServiceRequest.builder
      .service(s"frontend_service_$stage")
      .cluster(s"frontend_$stage")
      .forceNewDeployment(true)
      .build()
    ecsClient.updateService(updateServiceRequest)
  }

  def rotate(): List[Message] = {
    val putParameterBuilder = PutParameterRequest.builder
      .overwrite(true)
      .`type`(ParameterType.SECURE_STRING)

    clients.map {
      case (tdrClient, ssmParameterName) =>
        Try {
          val clients = keycloakClient.realm("tdr").clients()
          val clientId = clients.findByClientId(tdrClient).get(0).getId
          val client = clients.get(clientId)
          val newSecret = client.generateNewSecret().getValue
          logger.info(s"Secrets generated for $tdrClient")
          val putParameterRequest = putParameterBuilder
            .name(ssmParameterName)
            .value(newSecret).build()
          ssmClient.putParameter(putParameterRequest)
          logger.info(s"Parameter name $ssmParameterName updated for $tdrClient")
          restartFrontEndService()
          Message(s"Client $tdrClient has been rotated successfully")
        } match {
          case Failure(exception) =>
            logger.error("Error updating client secret", exception)
            Message(s"Client $tdrClient has failed ${exception.getMessage}")
          case Success(result) => result
        }
    }.toList
  }
}
object RotateClientSecrets {
  val ssmClient: SsmClient = SsmClient.builder()
    .region(Region.EU_WEST_2)
    .build()

  val ecsClient: EcsClient = EcsClient.builder()
    .region(Region.EU_WEST_2)
    .build()

  val clients: Map[String, String] = Map(
    "tdr"-> s"/$environment/keycloak/client/secret",
    "tdr-backend-checks"-> s"/$environment/keycloak/backend_checks_client/secret",
    "tdr-realm-admin"-> s"/$environment/keycloak/realm_admin_client/secret",
    "tdr-reporting"-> s"/$environment/keycloak/reporting_client/secret",
    "tdr-rotate-secrets"-> s"/$environment/keycloak/rotate_secrets_client/secret",
    "tdr-user-admin"-> s"/$environment/keycloak/user_admin_client/secret",
    "tdr-user-read"-> s"/$environment/keycloak/user_read/secret"
  )
  def apply(client: Keycloak) = new RotateClientSecrets(client, ssmClient, ecsClient, environment, clients)
}
