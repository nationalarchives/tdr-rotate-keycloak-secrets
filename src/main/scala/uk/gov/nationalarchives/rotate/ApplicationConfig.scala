package uk.gov.nationalarchives.rotate

import com.typesafe.config.{Config, ConfigFactory}

object ApplicationConfig {

  private val config: Config = ConfigFactory.load()
  val environment: String = config.getString("environment")
  val authUrl: String = config.getString("keycloak.url")
  val userAdminClient: String = config.getString("keycloak.user.admin.client")
  val secretPath: String = config.getString("keycloak.user.admin.secret_path")
  val ssmEndpoint: String = config.getString("ssm.endpoint")
  val snsEndpoint: String = config.getString("sns.endpoint")
  val snsTopic: String = config.getString("sns.topic")
  val consignmentApiConnectionName: String = config.getString("eventBridge.consignmentApiConnectionName")
}
