import sbt._

object Dependencies {
  private val circeVersion = "0.14.14"
  private val awsSdkVersion = "2.32.33"

  lazy val awsSns = "software.amazon.awssdk" % "sns" % awsSdkVersion
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % awsSdkVersion
  lazy val awsEcs = "software.amazon.awssdk" % "ecs" % awsSdkVersion
  lazy val awsEventBridge = "software.amazon.awssdk" % "eventbridge" % awsSdkVersion
  lazy val circeCore = "io.circe" %% "circe-core" % circeVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  lazy val circeParser = "io.circe" %% "circe-parser" % circeVersion
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % "26.0.6"
  lazy val lambdaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.3.0"
  lazy val mockito = "org.mockito" %% "mockito-scala" % "2.0.0"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"
  lazy val slf4j = "org.slf4j" % "slf4j-simple" % "2.0.17"
  lazy val typesafe = "com.typesafe" % "config" % "1.4.4"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "3.0.1"
}
