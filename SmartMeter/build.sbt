lazy val root = (project in file(".")).
  settings(
    name := "SmartMeter",
    version := "SNAPSHOT",
    scalaVersion := "2.11.8"
  )

libraryDependencies += "ch.ninecode.cim" % "cimscala_2.11" % "2.0.1-1.8.0" 
libraryDependencies += "org.apache.spark" % "spark-core_2.11" % "2.0.1"
libraryDependencies += "org.apache.spark" % "spark-sql_2.11" % "2.0.1"
libraryDependencies += "org.apache.spark" % "spark-hive-thriftserver_2.11" % "2.0.1"
libraryDependencies += "org.apache.spark" % "spark-graphx_2.11" % "2.0.1"

