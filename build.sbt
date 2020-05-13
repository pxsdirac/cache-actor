name := "cache-actor"

version := "0.1"

scalaVersion := "2.12.9"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.4",
  // https://mvnrepository.com/artifact/org.slf4j/slf4j-simple
   "org.slf4j" % "slf4j-simple" % "1.7.25"
)
