package uk.gov.nationalarchives.rotate

import com.amazonaws.services.lambda.runtime.Context
import org.keycloak.admin.client.Keycloak

import scala.annotation.unused

class Lambda() {
  val messageSender: MessageSender = MessageSender()
  val client: Keycloak = KeycloakAdminClient().client
  val rotateClientSecrets: RotateClientSecrets = RotateClientSecrets(client)

  def handleRequest(@unused input: String, @unused context: Context): Unit = {
    messageSender.sendMessages(rotateClientSecrets.rotate())
  }
}
