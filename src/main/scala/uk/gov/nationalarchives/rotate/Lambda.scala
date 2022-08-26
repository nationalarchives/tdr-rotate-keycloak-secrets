package uk.gov.nationalarchives.rotate

import com.amazonaws.services.lambda.runtime.Context
import org.keycloak.admin.client.Keycloak
import uk.gov.nationalarchives.rotate.ApplicationConfig.environment
import uk.gov.nationalarchives.rotate.MessageSender.Message

import java.io.{InputStream, OutputStream}
import scala.annotation.unused

class Lambda() {
  val messageSender: MessageSender = MessageSender()
  val client: Keycloak = KeycloakAdminClient().client
  val rotateClientSecrets: RotateClientSecrets = RotateClientSecrets(client)
  val rotateRealmKeys: RotateRealmKeys = RotateRealmKeys(client)

  def handleRequest(@unused input: InputStream, @unused output: OutputStream, @unused context: Context): Unit = {
    val secretRotationMessages: List[Message] = rotateClientSecrets.rotate()
    val realmKeyRotationMessage: Message = rotateRealmKeys.rotate()
    val messagesHeader: Message = Message(s"Keycloak rotation has been run for environment $environment")
    messageSender.sendMessages(List(messagesHeader, realmKeyRotationMessage) ::: secretRotationMessages)
  }
}
