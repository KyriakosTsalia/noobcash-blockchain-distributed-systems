name := "noobcash"

version := "1.0.0"

scalaVersion := "2.12.8"

lazy val akkaVersion = "2.6.4"
lazy val protobufVersion = "3.6.1"
lazy val akkaHttpVersion = "10.1.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,

  // AKKA REMOTING AND CLUSTERING
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,

  // FOR EXTERNAL AERON-UDP DRIVER
  "io.aeron" % "aeron-driver" % "1.27.0",
  "io.aeron" % "aeron-client" % "1.27.0",
  
  // AKKA STREAMS
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,

  // AKKA HTTP
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,

  // CHILL - KRYO SERIALIZATION
  "com.twitter" %% "chill-akka" % "0.9.5"
)

assemblyJarName in assembly := s"${name.value}_${scalaBinaryVersion.value}-${akkaVersion}_${version.value}.jar"