import Dependencies._

ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "uk.gov.nationalarchives.rotate"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-rotate-keycloak-secrets",
    libraryDependencies ++= Seq(
      awsEcs,
      awsSns,
      awsSsm,
      circeCore,
      circeGeneric,
      circeParser % Test,
      keycloakAdminClient,
      lambdaCore,
      mockito % Test,
      slf4j,
      typesafe,
      scalaTest % Test,
      wiremock
    ),
    Test / fork := true,
    Test / javaOptions += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf",
    assembly / assemblyJarName := "rotate-keycloak-secrets.jar",
    Test / parallelExecution := false,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs@_*) =>
        xs map {
          _.toLowerCase
        } match {
          case "services" :: _ =>
            MergeStrategy.filterDistinctLines
          case _ => MergeStrategy.discard
        }
      case "reference.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
