import com.jsuereth.sbtpgp.PgpKeys._

val Organization = "io.github.gitbucket"
val Name = "gitbucket"
val GitBucketVersion = "4.43.0"
val ScalatraVersion = "3.1.2"
val JettyVersion = "10.0.25"
val JgitVersion = "6.10.1.202505221210-r"

lazy val root = (project in file("."))
  .enablePlugins(SbtTwirl, ContainerPlugin)

sourcesInBase := false
organization := Organization
name := Name
version := GitBucketVersion
scalaVersion := "2.13.16"

crossScalaVersions += "3.7.2"

// scalafmtOnCompile := true

coverageExcludedPackages := ".*\\.html\\..*"

libraryDependencies ++= Seq(
  "org.eclipse.jgit"          % "org.eclipse.jgit.http.server" % JgitVersion,
  "org.eclipse.jgit"          % "org.eclipse.jgit.archive"     % JgitVersion,
  "org.scalatra"             %% "scalatra-javax"               % ScalatraVersion,
  "org.scalatra"             %% "scalatra-json-javax"          % ScalatraVersion,
  "org.scalatra"             %% "scalatra-forms-javax"         % ScalatraVersion,
  "org.json4s"               %% "json4s-jackson"               % "4.1.0-M8",
  "commons-io"                % "commons-io"                   % "2.20.0",
  "io.github.gitbucket"       % "solidbase"                    % "1.1.0",
  "io.github.gitbucket"       % "markedj"                      % "1.0.20",
  "org.tukaani"               % "xz"                           % "1.10",
  "org.apache.commons"        % "commons-compress"             % "1.28.0",
  "org.apache.commons"        % "commons-email"                % "1.6.0",
  "commons-net"               % "commons-net"                  % "3.11.1",
  "org.apache.httpcomponents" % "httpclient"                   % "4.5.14",
  "org.apache.sshd"           % "apache-sshd"                  % "2.15.0" exclude ("org.slf4j", "slf4j-jdk14") exclude (
    "org.apache.sshd",
    "sshd-mina"
  ) exclude ("org.apache.sshd", "sshd-netty")
    exclude ("org.apache.sshd", "sshd-spring-sftp"),
  "org.apache.tika"                 % "tika-core"                % "3.2.1",
  "com.github.takezoe"             %% "blocking-slick"           % "0.0.14",
  "com.novell.ldap"                 % "jldap"                    % "2009-10-07",
  "com.h2database"                  % "h2"                       % "2.3.232",
  "org.mariadb.jdbc"                % "mariadb-java-client"      % "2.7.12",
  "org.postgresql"                  % "postgresql"               % "42.7.7",
  "ch.qos.logback"                  % "logback-classic"          % "1.5.18",
  "com.zaxxer"                      % "HikariCP"                 % "7.0.0" exclude ("org.slf4j", "slf4j-api"),
  "com.typesafe"                    % "config"                   % "1.4.4",
  "fr.brouillard.oss.security.xhub" % "xhub4j-core"              % "1.1.0",
  "io.github.java-diff-utils"       % "java-diff-utils"          % "4.16",
  "org.cache2k"                     % "cache2k-all"              % "1.6.0.Final",
  "net.coobird"                     % "thumbnailator"            % "0.4.20",
  "com.github.zafarkhaja"           % "java-semver"              % "0.10.2",
  "com.nimbusds"                    % "oauth2-oidc-sdk"          % "11.27",
  "org.eclipse.jetty"               % "jetty-webapp"             % JettyVersion    % "provided",
  "javax.servlet"                   % "javax.servlet-api"        % "3.1.0"         % "provided",
  "junit"                           % "junit"                    % "4.13.2"        % "test",
  "org.scalatra"                   %% "scalatra-scalatest-javax" % ScalatraVersion % "test",
  "org.mockito"                     % "mockito-core"             % "5.18.0"        % "test",
  "com.dimafeng"                   %% "testcontainers-scala"     % "0.43.0"        % "test",
  "org.testcontainers"              % "mysql"                    % "1.21.3"        % "test",
  "org.testcontainers"              % "postgresql"               % "1.21.3"        % "test",
  "net.i2p.crypto"                  % "eddsa"                    % "0.3.0",
  "is.tagomor.woothee"              % "woothee-java"             % "1.11.0",
  "org.ec4j.core"                   % "ec4j-core"                % "1.1.1",
  "org.kohsuke"                     % "github-api"               % "1.329"         % "test"
)

// Compiler settings
scalacOptions := Seq(
  "-deprecation",
  "-language:postfixOps",
  "-opt:l:method",
  "-feature",
  "-Wunused:imports",
  "-Wconf:cat=unused&src=twirl/.*:s,cat=unused&src=scala/gitbucket/core/model/[^/]+\\.scala:s"
)
scalacOptions ++= {
  scalaBinaryVersion.value match {
    case "2.13" =>
      Seq("-Xsource:3-cross")
    case _ =>
      Nil
  }
}
compile / javacOptions ++= Seq("-target", "11", "-source", "11")
Container / javaOptions += "-Dlogback.configurationFile=/logback-dev.xml"

// Test settings
//testOptions in Test += Tests.Argument("-l", "ExternalDBTest")
Test / javaOptions += "-Dgitbucket.home=target/gitbucket_home_for_test"
Test / testOptions += Tests.Setup(() => new java.io.File("target/gitbucket_home_for_test").mkdir())
Test / fork := true

// Packaging options
packageOptions += Package.MainClass("JettyLauncher")

// Assembly settings
assembly / test := {}
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) =>
    (xs map { _.toLowerCase }) match {
      case ("manifest.mf" :: Nil) => MergeStrategy.discard
      case _                      => MergeStrategy.discard
    }
  case x => MergeStrategy.first
}

// Exclude a war file from published artifacts
signedArtifacts := {
  signedArtifacts.value.filterNot { case (_, file) =>
    file.getName.endsWith(".war") || file.getName.endsWith(".war.asc")
  }
}

// Create executable war file
val ExecutableConfig = config("executable").hide
Keys.ivyConfigurations += ExecutableConfig
libraryDependencies ++= Seq(
  "org.eclipse.jetty" % "jetty-security" % JettyVersion % "executable",
  "org.eclipse.jetty" % "jetty-webapp"   % JettyVersion % "executable",
  "org.eclipse.jetty" % "jetty-server"   % JettyVersion % "executable",
  "org.eclipse.jetty" % "jetty-xml"      % JettyVersion % "executable",
  "org.eclipse.jetty" % "jetty-http"     % JettyVersion % "executable",
  "org.eclipse.jetty" % "jetty-servlet"  % JettyVersion % "executable",
  "org.eclipse.jetty" % "jetty-io"       % JettyVersion % "executable",
  "org.eclipse.jetty" % "jetty-util"     % JettyVersion % "executable"
)

// Run package task before test to generate target/webapp for integration test
Test / test := {
  _root_.sbt.Keys.`package`.value
  (Test / test).value
}

val executableKey = TaskKey[File]("executable")
executableKey := {
  import java.util.jar.Attributes.{Name => AttrName}
  import java.util.jar.{Manifest => JarManifest}

  val workDir = Keys.target.value / "executable"
  val warName = Keys.name.value + ".war"

  val log = streams.value.log
  log info s"building executable webapp in ${workDir}"

  // initialize temp directory
  val temp = workDir / "webapp"
  IO delete temp

  // include jetty classes
  val jettyJars = Keys.update.value select configurationFilter(name = ExecutableConfig.name)
  jettyJars foreach { jar =>
    IO unzip (
      jar,
      temp,
      (name: String) => (name startsWith "javax/") || (name startsWith "org/") || (name startsWith "META-INF/services/")
    )
  }

  // include original war file
  val warFile = (Keys.`package`).value
  IO unzip (warFile, temp)

  // include launcher classes
  val classDir = (Compile / Keys.classDirectory).value
  val launchClasses = Seq("JettyLauncher.class" /*, "HttpsSupportConnector.class" */ )
  launchClasses foreach { name =>
    IO copyFile (classDir / name, temp / name)
  }

  // include plugins
  val pluginsDir = temp / "WEB-INF" / "classes" / "plugins"
  IO createDirectory (pluginsDir)

  val plugins = IO readLines (Keys.baseDirectory.value / "src" / "main" / "resources" / "bundle-plugins.txt")
  plugins.foreach { plugin =>
    plugin.trim.split(":") match {
      case Array(pluginId, pluginVersion) =>
        val url = "https://github.com/" +
          s"gitbucket/gitbucket-${pluginId}-plugin/releases/download/${pluginVersion}/gitbucket-${pluginId}-plugin-${pluginVersion}.jar"
        log info s"Download: ${url}"
        IO transfer (new java.net.URI(url).toURL.openStream, pluginsDir / url.substring(url.lastIndexOf("/") + 1))
      case _ => ()
    }
  }

  // zip it up
  IO delete (temp / "META-INF" / "MANIFEST.MF")
  val contentMappings = (temp.allPaths --- PathFinder(temp)).get pair { file =>
    IO.relativizeFile(temp, file)
  }
  val manifest = new JarManifest
  manifest.getMainAttributes put (AttrName.MANIFEST_VERSION, "1.0")
  manifest.getMainAttributes put (AttrName.MAIN_CLASS, "JettyLauncher")
  val outputFile = workDir / warName
  IO jar (contentMappings.map { case (file, path) => (file, path.toString) }, outputFile, manifest, None)

  // generate checksums
  Seq(
    "md5" -> "MD5",
    "sha1" -> "SHA-1",
    "sha256" -> "SHA-256"
  ).foreach { case (extension, algorithm) =>
    val checksumFile = workDir / (warName + "." + extension)
    Checksums generate (outputFile, checksumFile, algorithm)
  }

  // done
  log info s"built executable webapp ${outputFile}"
  outputFile
}
publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
publishMavenStyle := true
pomIncludeRepository := { _ =>
  false
}
pomExtra := (
  <url>https://github.com/gitbucket/gitbucket</url>
  <licenses>
    <license>
      <name>The Apache Software License, Version 2.0</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <scm>
    <url>https://github.com/gitbucket/gitbucket</url>
    <connection>scm:git:https://github.com/gitbucket/gitbucket.git</connection>
  </scm>
  <developers>
    <developer>
      <id>takezoe</id>
      <name>Naoki Takezoe</name>
      <url>https://github.com/takezoe</url>
    </developer>
    <developer>
      <id>shimamoto</id>
      <name>Takako Shimamoto</name>
      <url>https://github.com/shimamoto</url>
    </developer>
    <developer>
      <id>tanacasino</id>
      <name>Tomofumi Tanaka</name>
      <url>https://github.com/tanacasino</url>
    </developer>
    <developer>
      <id>mrkm4ntr</id>
      <name>Shintaro Murakami</name>
      <url>https://github.com/mrkm4ntr</url>
    </developer>
    <developer>
      <id>nazoking</id>
      <name>nazoking</name>
      <url>https://github.com/nazoking</url>
    </developer>
    <developer>
      <id>McFoggy</id>
      <name>Matthieu Brouillard</name>
      <url>https://github.com/McFoggy</url>
    </developer>
  </developers>
)

Test / testOptions ++= {
  if (scala.util.Properties.isWin) {
    Seq(
      Tests.Exclude(
        Set(
          "gitbucket.core.GitBucketCoreModuleSpec"
        )
      )
    )
  } else {
    Nil
  }
}

Container / javaOptions ++= Seq(
  "-Dlogback.configurationFile=/logback-dev.xml",
  "-Xdebug",
  "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000",
  "-Dorg.eclipse.jetty.annotations.AnnotationParser.LEVEL=OFF",
  // "-Ddev-features=keep-session"
)
Container / containerLibs := Seq(("org.eclipse.jetty" % "jetty-runner" % JettyVersion).intransitive())
Container / containerMain := "org.eclipse.jetty.runner.Runner"
