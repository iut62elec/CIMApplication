package ch.ninecode.gl

import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Calendar

import scala.collection.mutable.HashMap
import scala.io.Source
import scala.tools.nsc.io.Jar
import scala.util.Random

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.rdd.RDD
import org.apache.spark.graphx.EdgeRDD
import org.apache.spark.graphx.VertexId
import org.apache.spark.graphx.VertexRDD
import org.apache.spark.graphx.Graph

import ch.ninecode.cim._
import ch.ninecode.model._

object Main
{
    val APPLICATION_NAME = "GridLAB-D"
    val APPLICATION_VERSION = "2.0.0"

    object LogLevels extends Enumeration
    {
        type LogLevels = Value
        val ALL, DEBUG, ERROR, FATAL, INFO, OFF, TRACE, WARN = Value
    }
    implicit val LogLevelsRead: scopt.Read[LogLevels.Value] = scopt.Read.reads (LogLevels withName _)

    implicit val mapRead: scopt.Read[Map[String,String]] = scopt.Read.reads (
    (s) =>
        {
            var ret = Map[String, String] ()
            val ss = s.split (",")
            for (p <- ss)
            {
                val kv = p.split ("=")
                ret = ret + ((kv(0), kv(1)))
            }
            ret
        }
    )

    case class Arguments (
        master: String = "",
        opts: Map[String,String] = Map(),
        three: Boolean = false,
        precalculation: Boolean = false,
        all: Boolean = false,
        clean: Boolean = false,
        erase: Boolean = false,
        log_level: LogLevels.Value = LogLevels.OFF,
        checkpoint_dir: String = "",
        files: Seq[String] = Seq()
    )

    val parser = new scopt.OptionParser[Arguments](APPLICATION_NAME)
    {
        head (APPLICATION_NAME, APPLICATION_VERSION)

        opt[String]('m', "master").valueName ("MASTER_URL").
            action ((x, c) => c.copy (master = x)).
            text ("spark://host:port, mesos://host:port, yarn, or local[*]")

        opt[Map[String,String]]('o', "opts").valueName ("k1=v1,k2=v2").
            action ((x, c) => c.copy (opts = x)).
            text ("other Spark options")

        opt[Unit]('3', "three").
            action ((_, c) => c.copy (three = true)).
            text ("use three phase computations")
            
        opt[Unit]('p', "precalculation").
            action ((_, c) => c.copy (precalculation = true)).
            text ("calculates threshold and EEA existence for all HAS, assuming no EEA")

        opt[Unit]('a', "all").
            action ((_, c) => c.copy (all = true)).
            text ("process all transformers (no pre-calculation)")

        opt[Unit]('c', "clean").
            action ((_, c) => c.copy (clean = true)).
            text ("clean up (delete) simulation local files")

        opt[Unit]('e', "erase").
            action ((_, c) => c.copy (erase = true)).
            text ("clean up (delete) simulation HDFS files")

        opt[LogLevels.Value]('l', "logging").
            action ((x, c) => c.copy (log_level = x)).
            text ("log level, one of " + LogLevels.values.iterator.mkString (","))

        opt[String]('k', "checkpointdir").valueName ("<dir>").
            action ((x, c) => c.copy (checkpoint_dir = x)).
            text ("checkpoint directory on HDFS, e.g. hdfs://...")

        help ("help").text ("prints this usage text")

        arg[String]("<CIM> <CIM> ...").unbounded ().
            action ((x, c) => c.copy (files = c.files :+ x)).
            text ("CIM rdf files to process")

    }

    def jarForObject (obj: Object): String =
    {
        // see https://stackoverflow.com/questions/320542/how-to-get-the-path-of-a-running-jar-file
        var ret = obj.getClass.getProtectionDomain ().getCodeSource ().getLocation ().getPath ()
        try
        {
            ret = URLDecoder.decode (ret, "UTF-8")
        }
        catch
        {
            case e: UnsupportedEncodingException => e.printStackTrace ()
        }
        if (!ret.toLowerCase ().endsWith (".jar"))
        {
            // as an aid to debugging, make jar in tmp and pass that name
            val name = "/tmp/" + Random.nextInt (99999999) + ".jar"
            val writer = new Jar (new scala.reflect.io.File (new java.io.File (name))).jarWriter ()
            writer.addDirectory (new scala.reflect.io.Directory (new java.io.File (ret + "ch/")), "ch/")
            writer.close ()
            ret = name
        }

        return (ret)
    }

    /**
     * Build jar with dependencies (target/GridLAB-D-2.0.0-jar-with-dependencies.jar):
     *     mvn package
     * Invoke (on the cluster) with:
     *     spark-submit --master spark://sandbox:7077 --conf spark.driver.memory=2g --conf spark.executor.memory=4g /opt/code/GridLAB-D-2.0.0-jar-with-dependencies.jar hdfs://sandbox:8020/data/bkw_cim_export_haelig.rdf
     * or on AWS:
     *     /opt/spark/bin/spark-submit --master yarn /disktemp/transfer/GridLAB-D-2.0.0-jar-with-dependencies.jar hdfs://hmaster:9000/data/NIS_CIM_Export_sias_current_20161220_Sample4.rdf
     */
    def main (args: Array[String])
    {
        // parser.parse returns Option[C]
        parser.parse (args, Arguments ()) match
        {
            case Some (arguments) =>

                val begin = System.nanoTime ()

                // create the configuration
                val configuration = new SparkConf (false)
                configuration.setAppName (APPLICATION_NAME)
                if ("" != arguments.master)
                    configuration.setMaster (arguments.master)
                if (arguments.opts.size != 0)
                    arguments.opts.map ((pair: Tuple2[String, String]) => configuration.set (pair._1, pair._2))

                // get the necessary jar files to send to the cluster
                if ("" != arguments.master)
                {
                    val s1 = jarForObject (new DefaultSource ())
                    val s2 = jarForObject (new Complex (0.0, 0.0))
                    if (s1 != s2)
                        configuration.setJars (Array (s1, s2))
                    else
                        configuration.setJars (Array (s1))
                }

                // register low level classes
                configuration.registerKryoClasses (Array (classOf[Element], classOf[BasicElement], classOf[Unknown]))
                // register CIM case classes
                CHIM.apply_to_all_classes { x => configuration.registerKryoClasses (Array (x.runtime_class)) }
                // register edge related classes
                configuration.registerKryoClasses (Array (classOf[PreEdge], classOf[Extremum], classOf[PostEdge]))
                // register topological classes
                configuration.registerKryoClasses (Array (classOf[CuttingEdge], classOf[TopologicalData]))
                // register GridLAB-D classes
                configuration.registerKryoClasses (Array (
                    classOf[ch.ninecode.gl.PreNode],
                    classOf[ch.ninecode.gl.PreEdge],
                    classOf[ch.ninecode.gl.PV],
                    classOf[ch.ninecode.gl.Transformer],
                    classOf[ch.ninecode.gl.ThreePhaseComplexDataElement]))
                configuration.set ("spark.ui.showConsoleProgress", "false")

                // make a Spark session
                val session = SparkSession.builder ().config (configuration).getOrCreate ()
                session.sparkContext.setLogLevel (arguments.log_level.toString ())
                if ("" != arguments.checkpoint_dir)
                    session.sparkContext.setCheckpointDir (arguments.checkpoint_dir)

                val setup = System.nanoTime ()
                println ("setup : " + (setup - begin) / 1e9 + " seconds")

                val options = new HashMap[String, String] ()
                options.put ("path", arguments.files.mkString (","))
                options.put ("StorageLevel", "MEMORY_AND_DISK_SER")
                options.put ("ch.ninecode.cim.make_edges", "false")
                options.put ("ch.ninecode.cim.do_join", "false")
                options.put ("ch.ninecode.cim.do_topo", "true")
                options.put ("ch.ninecode.cim.do_topo_islands", "false")

                val elements = session.read.format ("ch.ninecode.cim").options (options).load (arguments.files:_*)
                // this fails with ClassCastException:
                //     val count = elements.count
                // cannot assign instance of scala.collection.immutable.List$SerializationProxy
                // to field org.apache.spark.sql.execution.RDDConversions$$anonfun$rowToRowRdd$1.outputTypes$2
                // of type scala.collection.Seq in instance of org.apache.spark.sql.execution.RDDConversions$$anonfun$rowToRowRdd$1
                //println ("" + count + " elements")
                elements.printSchema
                elements.explain
                val read = System.nanoTime ()
                println ("read : " + (read - setup) / 1e9 + " seconds")

                val hdfsuri =
                {
                    val name = arguments.files (0).replace (" ", "%20")
                    val uri = new URI (name)
                    uri.getScheme + "://" + uri.getAuthority + "/"
                }

                val gridlabd = new GridLABD (session)
                gridlabd.HDFS_URI = hdfsuri
                gridlabd.DELETE_INTERMEDIATE_FILES = arguments.clean
                gridlabd.DELETE_SIMULATION_FILES = arguments.erase
                gridlabd.USE_ONE_PHASE = !arguments.three
                gridlabd._StorageLevel = StorageLevel.MEMORY_AND_DISK_SER

                // prepare the initial graph edges and nodes
                val (xedges, xnodes) = gridlabd.prepare ()
//
// java.lang.ClassCastException: org.apache.spark.graphx.impl.ShippableVertexPartition cannot be cast to scala.Product2
// https://issues.apache.org/jira/browse/SPARK-14804 Graph vertexRDD/EdgeRDD checkpoint results ClassCastException: 
// Fix Version/s: 2.0.3, 2.1.1, 2.2.0
//                session.sparkContext.getCheckpointDir match
//                {
//                    case Some (dir) => initial.checkpoint ()
//                    case None =>
//                }

                val _transformers = new Transformers (session, gridlabd._StorageLevel)
                val tdata = _transformers.getTransformerData ()
                session.sparkContext.getCheckpointDir match
                {
                    case Some (dir) => tdata.checkpoint ()
                    case None =>
                }

                val cdata = gridlabd.getCableMaxCurrent ()
                session.sparkContext.getCheckpointDir match
                {
                    case Some (dir) => cdata.checkpoint ()
                    case None =>
                }

                val prepare = System.nanoTime ()
                println ("prepare: " + (prepare - read) / 1e9 + " seconds")

                // determine the set of transformers to work on
                var transformers = {
                    // do all low voltage power transformers
                    // ToDo: fix this 1kV multiplier on the voltages
                    val niederspannug = tdata.filter ((td) => td.voltage0 != 0.4 && td.voltage1 == 0.4)
                    niederspannug.groupBy (_.terminal1.TopologicalNode).values.map (_.toArray).collect
                }

                // do the pre-calculation
                val precalc_results =
                {
                    // construct the initial graph from the real edges and nodes
                    val initial = Graph.apply[PreNode, PreEdge] (xnodes, xedges, PreNode ("", 0.0), gridlabd._StorageLevel, gridlabd._StorageLevel)
                    PowerFeeding.threshold_calculation (initial, transformers, gridlabd)
                }

                val trafo = precalc_results.has.filter(_.has_eea).keyBy (a => gridlabd.trafokreis(a.source_obj)).groupByKey.map (_._2.head.source_obj).collect
                println ("" + trafo.length + " transformers to process:")
                println (trafo.map (a => gridlabd.trafokreis(a)).mkString ("\n"))

                // do gridlab simulation if not only pre-calculation
                if (!arguments.precalculation)
                {
                    def do_one_trafofreis (s: Array[TData]): Int =
                    {
                        val simulation = gridlabd.trafokreis (s)
                        val rdd = gridlabd.einspeiseleistung (precalc_results, tdata, cdata) (s)
                        val id = Database.store ("Einspeiseleistung", Calendar.getInstance ()) (simulation, rdd)
                        gridlabd.cleanup (simulation)
                        id
                    }
                    val results = trafo.par.map (do_one_trafofreis)
                }

                val calculate = System.nanoTime ()
                println ("calculate: " + (calculate - prepare) / 1e9 + " seconds")

                println ("total: " + (calculate - begin) / 1e9 + " seconds")

                sys.exit (0)
            case None =>
                sys.exit (1)
        }
    }
}
