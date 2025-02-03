package uk.gov.nationalarchives.rotate

import org.keycloak.admin.client.Keycloak
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.{UpdateServiceRequest, UpdateServiceResponse}
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.{UpdateConnectionAuthRequestParameters, UpdateConnectionOAuthClientRequestParameters, UpdateConnectionOAuthRequestParameters, UpdateConnectionRequest}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{ParameterType, PutParameterRequest}
import uk.gov.nationalarchives.rotate.ApplicationConfig._
import uk.gov.nationalarchives.rotate.MessageSender.Message
import uk.gov.nationalarchives.rotate.RotateClientSecrets.{ApiConnectionClient, ClientSecretRotationResult}

import scala.util.{Failure, Success, Try}

class RotateClientSecrets(keycloakClient: Keycloak,
                          ssmClient: SsmClient,
                          ecsClient: EcsClient,
                          eventBridgeClient: EventBridgeClient,
                          stage: String,
                          clients: Map[String, String],
                          apiConnectionClients: Set[ApiConnectionClient]
                         ) {

  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)

  private case class EcsService(serviceName: String, clusterName: String)

  private val ecsServices: Set[EcsService] = Set(
    EcsService(s"frontend_service_$stage", s"frontend_$stage"),
    EcsService(s"transferservice_service_$stage", s"transferservice_$stage")
  )

  private def updateEventBridgeConnectionSecret(connectionName: String, tdrClientId: String, secretValue: String): Message = {

    val updateSecretRequest: UpdateConnectionOAuthClientRequestParameters = UpdateConnectionOAuthClientRequestParameters.builder()
      .clientID(tdrClientId)
      .clientSecret(secretValue)
      .build()

    val updateOAuthRequest: UpdateConnectionOAuthRequestParameters = UpdateConnectionOAuthRequestParameters.builder()
      .clientParameters(updateSecretRequest)
      .build()

    val updateConnectionAuthRequest = UpdateConnectionAuthRequestParameters.builder()
      .oAuthParameters(updateOAuthRequest)
      .build()

    val updateConnectionRequest = UpdateConnectionRequest.builder()
      .authorizationType("OAUTH_CLIENT_CREDENTIALS")
      .name(connectionName)
      .authParameters(updateConnectionAuthRequest)
      .build()

    Try {
      eventBridgeClient.updateConnection(updateConnectionRequest)
      logger.info(s"EventBridge connection $connectionName secret updated")
      Message(s"EventBridge connections secrets using $tdrClientId updated")
    } match {
      case Failure(exception) =>
        logger.error("Error updating client secret", exception)
        Message(s"EventBridge connections secrets updating has failed: ${exception.getMessage}")
      case Success(result) => result
    }
  }

  private def restartEcsService(ecsService: EcsService): UpdateServiceResponse = {
    val updateServiceRequest = UpdateServiceRequest.builder
      .service(ecsService.serviceName)
      .cluster(ecsService.clusterName)
      .forceNewDeployment(true)
      .build()
    ecsClient.updateService(updateServiceRequest)
  }

  def rotate(): List[Message] = {
    val putParameterBuilder = PutParameterRequest.builder
      .overwrite(true)
      .`type`(ParameterType.SECURE_STRING)

    val messages: List[Message] = clients.map {
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
          ClientSecretRotationResult(tdrClient, Message(s"Client $tdrClient secret has been rotated successfully"), Some(newSecret))
        } match {
          case Failure(exception) =>
            logger.error("Error updating client secret", exception)
            Message(s"Client $tdrClient has failed ${exception.getMessage}") :: Nil
          case Success(result) =>
            val resultClient = result.tdrClient
            apiConnectionClients.find(_.tdrClient == resultClient) match {
              case Some(connectionClient) =>
                List(
                  result.resultMessage,
                  updateEventBridgeConnectionSecret(connectionClient.connectionName, resultClient, result.newSecretValue.get)
                )
              case None =>
                List(result.resultMessage)
            }
        }
    }.toList.flatten

    Try {
      ecsServices.foreach(restartEcsService)
    } match {
      case
        Failure(exception) => logger.error("Error restarting ECS", exception)
        messages :+ Message(s"ECS task failed to restart: ${exception.getMessage}")
      case Success(_) => messages
    }
  }
}
object RotateClientSecrets {
  case class ApiConnectionClient(tdrClient: String, connectionName: String)

  val ssmClient: SsmClient = SsmClient.builder()
    .region(Region.EU_WEST_2)
    .build()

  private val ecsClient: EcsClient = EcsClient.builder()
    .region(Region.EU_WEST_2)
    .build()

  private def eventBridgeClient = EventBridgeClient.builder()
    .region(Region.EU_WEST_2)
    .build()

  private val tdrDraftMetadataClient = "tdr-draft-metadata"

  val clients: Map[String, String] = Map(
    "tdr"-> s"/$environment/keycloak/client/secret",
    "tdr-backend-checks"-> s"/$environment/keycloak/backend_checks_client/secret",
    "tdr-realm-admin"-> s"/$environment/keycloak/realm_admin_client/secret",
    "tdr-reporting"-> s"/$environment/keycloak/reporting_client/secret",
    "tdr-rotate-secrets"-> s"/$environment/keycloak/rotate_secrets_client/secret",
    "tdr-transfer-service"-> s"/$environment/keycloak/transfer_service_client/secret",
    "tdr-user-admin"-> s"/$environment/keycloak/user_admin_client/secret",
    "tdr-user-read"-> s"/$environment/keycloak/user_read_client/secret",
    "tdr-draft-metadata"-> s"/$environment/keycloak/draft_metadata_client/secret"
  )

  val apiConnectionClients: Set[ApiConnectionClient] = Set(
    ApiConnectionClient(tdrDraftMetadataClient, consignmentApiConnectionName)
  )

  case class ClientSecretRotationResult(tdrClient: String, resultMessage: Message, newSecretValue: Option[String])

  def apply(client: Keycloak) = new RotateClientSecrets(
    client, ssmClient, ecsClient, eventBridgeClient, environment, clients, apiConnectionClients)
}
