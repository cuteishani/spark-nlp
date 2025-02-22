/*
 * Copyright 2017-2021 John Snow Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.johnsnowlabs.nlp.annotators.ner.dl

import com.johnsnowlabs.client.aws.AWSGateway

import java.io.File
import com.johnsnowlabs.ml.crf.TextSentenceLabels
import com.johnsnowlabs.ml.tensorflow._
import com.johnsnowlabs.nlp.AnnotatorType.{DOCUMENT, NAMED_ENTITY, TOKEN, WORD_EMBEDDINGS}
import com.johnsnowlabs.nlp.annotators.common.{NerTagged, WordpieceEmbeddingsSentence}
import com.johnsnowlabs.nlp.annotators.ner.{NerApproach, Verbose}
import com.johnsnowlabs.nlp.annotators.param.ExternalResourceParam
import com.johnsnowlabs.nlp.util.io.{ExternalResource, ReadAs, ResourceHelper}
import com.johnsnowlabs.nlp.{AnnotatorApproach, AnnotatorType, ParamsAndFeaturesWritable}
import com.johnsnowlabs.storage.HasStorageRef
import com.johnsnowlabs.util.{ConfigHelper, ConfigLoader}
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.SystemUtils
import org.apache.spark.SparkFiles
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.param._
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.tensorflow.Graph
import org.tensorflow.proto.framework.GraphDef

import scala.collection.mutable
import scala.util.Random

/**
 * This Named Entity recognition annotator allows to train generic NER model based on Neural Networks.
 *
 * The architecture of the neural network is a Char CNNs - BiLSTM - CRF that achieves state-of-the-art in most datasets.
 *
 * For instantiated/pretrained models, see [[NerDLModel]].
 *
 * The training data should be a labeled Spark Dataset, in the format of [[com.johnsnowlabs.nlp.training.CoNLL CoNLL]]
 * 2003 IOB with `Annotation` type columns. The data should have columns of type `DOCUMENT, TOKEN, WORD_EMBEDDINGS` and an
 * additional label column of annotator type `NAMED_ENTITY`.
 * Excluding the label, this can be done with for example
 *   - a [[com.johnsnowlabs.nlp.annotators.sbd.pragmatic.SentenceDetector SentenceDetector]],
 *   - a [[com.johnsnowlabs.nlp.annotators.Tokenizer Tokenizer]] and
 *   - a [[com.johnsnowlabs.nlp.embeddings.WordEmbeddingsModel WordEmbeddingsModel]]
 *     (any embeddings can be chosen, e.g. [[com.johnsnowlabs.nlp.embeddings.BertEmbeddings BertEmbeddings]] for BERT based embeddings).
 *
 * For extended examples of usage, see the [[https://github.com/JohnSnowLabs/spark-nlp-workshop/tree/master/jupyter/training/english/dl-ner Spark NLP Workshop]]
 * and the [[https://github.com/JohnSnowLabs/spark-nlp/blob/master/src/test/scala/com/johnsnowlabs/nlp/annotators/ner/dl/NerDLSpec.scala NerDLSpec]].
 *
 * ==Example==
 * {{{
 * import com.johnsnowlabs.nlp.base.DocumentAssembler
 * import com.johnsnowlabs.nlp.annotators.Tokenizer
 * import com.johnsnowlabs.nlp.annotators.sbd.pragmatic.SentenceDetector
 * import com.johnsnowlabs.nlp.embeddings.BertEmbeddings
 * import com.johnsnowlabs.nlp.annotators.ner.dl.NerDLApproach
 * import com.johnsnowlabs.nlp.training.CoNLL
 * import org.apache.spark.ml.Pipeline
 *
 * // First extract the prerequisites for the NerDLApproach
 * val documentAssembler = new DocumentAssembler()
 *   .setInputCol("text")
 *   .setOutputCol("document")
 *
 * val sentence = new SentenceDetector()
 *   .setInputCols("document")
 *   .setOutputCol("sentence")
 *
 * val tokenizer = new Tokenizer()
 *   .setInputCols("sentence")
 *   .setOutputCol("token")
 *
 * val embeddings = BertEmbeddings.pretrained()
 *   .setInputCols("sentence", "token")
 *   .setOutputCol("embeddings")
 *
 * // Then the training can start
 * val nerTagger = new NerDLApproach()
 *   .setInputCols("sentence", "token", "embeddings")
 *   .setLabelColumn("label")
 *   .setOutputCol("ner")
 *   .setMaxEpochs(1)
 *   .setRandomSeed(0)
 *   .setVerbose(0)
 *
 * val pipeline = new Pipeline().setStages(Array(
 *   documentAssembler,
 *   sentence,
 *   tokenizer,
 *   embeddings,
 *   nerTagger
 * ))
 *
 * // We use the text and labels from the CoNLL dataset
 * val conll = CoNLL()
 * val trainingData = conll.readDataset(spark, "src/test/resources/conll2003/eng.train")
 *
 * val pipelineModel = pipeline.fit(trainingData)
 * }}}
 *
 * @see [[com.johnsnowlabs.nlp.annotators.ner.crf.NerCrfApproach NerCrfApproach]] for a generic CRF approach
 * @see [[com.johnsnowlabs.nlp.annotators.ner.NerConverter NerConverter]] to further process the results
 * @param uid required uid for storing annotator to disk
 * @groupname anno Annotator types
 * @groupdesc anno Required input and expected output annotator types
 * @groupname Ungrouped Members
 * @groupname param Parameters
 * @groupname setParam Parameter setters
 * @groupname getParam Parameter getters
 * @groupname Ungrouped Members
 * @groupprio param  1
 * @groupprio anno  2
 * @groupprio Ungrouped 3
 * @groupprio setParam  4
 * @groupprio getParam  5
 * @groupdesc param A list of (hyper-)parameter keys this annotator can take. Users can set and get the parameter values through setters and getters, respectively.
 * */
class NerDLApproach(override val uid: String)
  extends AnnotatorApproach[NerDLModel]
    with NerApproach[NerDLApproach]
    with Logging
    with ParamsAndFeaturesWritable {

  def this() = this(Identifiable.randomUID("NerDL"))

  override def getLogName: String = "NerDL"

  /** Trains Tensorflow based Char-CNN-BLSTM model */
  override val description = "Trains Tensorflow based Char-CNN-BLSTM model"

  /** Input annotator types: DOCUMENT, TOKEN, WORD_EMBEDDINGS
   *
   * @group anno
   * */
  override val inputAnnotatorTypes: Array[String] = Array(DOCUMENT, TOKEN, WORD_EMBEDDINGS)

  /** Output annotator types: NAMED_ENTITY
   *
   * @group anno
   * */
  override val outputAnnotatorType: String = NAMED_ENTITY

  /** Learning Rate (Default: `1e-3f`)
   *
   * @group param
   * */
  val lr = new FloatParam(this, "lr", "Learning Rate")
  /** Learning rate decay coefficient (Default: `0.005f`). Real Learning Rate calculates to `lr / (1 + po * epoch)`
   *
   * @group param
   * */
  val po = new FloatParam(this, "po", "Learning rate decay coefficient. Real Learning Rage = lr / (1 + po * epoch)")
  /** Batch size (Default: `8`)
   *
   * @group param
   * */
  val batchSize = new IntParam(this, "batchSize", "Batch size")
  /** Dropout coefficient (Default: `0.5f`)
   *
   * @group param
   * */
  val dropout = new FloatParam(this, "dropout", "Dropout coefficient")
  /** Folder path that contain external graph files
   *
   * @group param
   * */
  val graphFolder = new Param[String](this, "graphFolder", "Folder path that contain external graph files")
  /** ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()
   *
   * @group param
   * */
  val configProtoBytes = new IntArrayParam(this, "configProtoBytes", "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()")
  /** Whether to use contrib LSTM Cells (Default: `true`). Not compatible with Windows. Might slightly improve accuracy.
   *
   * @group param
   * */
  val useContrib = new BooleanParam(this, "useContrib", "Whether to use contrib LSTM Cells. Not compatible with Windows. Might slightly improve accuracy.")
  /** Choose the proportion of training dataset to be validated against the model on each Epoch (Default: `0.0f`).
   * The value should be between 0.0 and 1.0 and by default it is 0.0 and off.
   *
   * @group param
   * */
  val validationSplit = new FloatParam(this, "validationSplit", "Choose the proportion of training dataset to be validated against the model on each Epoch. The value should be between 0.0 and 1.0 and by default it is 0.0 and off.")
  /** Whether logs for validation to be extended (Default: `false`): it displays time and evaluation of each label
   *
   * @group param
   * */
  val evaluationLogExtended = new BooleanParam(this, "evaluationLogExtended", "Whether logs for validation to be extended: it displays time and evaluation of each label. Default is false.")
  /** Whether to output to annotators log folder (Default: `false`)
   *
   * @group param
   * */
  val enableOutputLogs = new BooleanParam(this, "enableOutputLogs", "Whether to output to annotators log folder")

  /** Path to test dataset. If set, it is used to calculate statistics on it during training.
   *
   * @group param
   * */
  val testDataset = new ExternalResourceParam(this, "testDataset", "Path to test dataset. If set, it is used to calculate statistics on it during training.")
  /** Whether to include confidence scores in annotation metadata (Default: `false`)
   *
   * @group param
   * */
  val includeConfidence = new BooleanParam(this, "includeConfidence", "Whether to include confidence scores in annotation metadata")

  /** whether to include all confidence scores in annotation metadata or just score of the predicted tag
   *
   * @group param
   * */
  val includeAllConfidenceScores = new BooleanParam(this, "includeAllConfidenceScores", "whether to include all confidence scores in annotation metadata")

  /** Folder path to save training logs (Default: `""`)
   *
   * @group param
   */
  val outputLogsPath = new Param[String](this, "outputLogsPath", "Folder path to save training logs")

  /** Whether to optimize for large datasets or not (Default: `false`). Enabling this option can slow down training.
   *
   * @group param
   */
  val enableMemoryOptimizer = new BooleanParam(this, "enableMemoryOptimizer", "Whether to optimize for large datasets or not. Enabling this option can slow down training.")

  /** Learning Rate
   *
   * @group getParam
   * */
  def getLr: Float = $(this.lr)

  /** Learning rate decay coefficient. Real Learning Rage = lr / (1 + po * epoch)
   *
   * @group getParam
   * */
  def getPo: Float = $(this.po)

  /** Batch size
   *
   * @group getParam
   * */
  def getBatchSize: Int = $(this.batchSize)

  /** Dropout coefficient
   *
   * @group getParam
   * */
  def getDropout: Float = $(this.dropout)

  /** ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()
   *
   * @group getParam
   * */
  def getConfigProtoBytes: Option[Array[Byte]] = get(this.configProtoBytes).map(_.map(_.toByte))

  /** Whether to use contrib LSTM Cells. Not compatible with Windows. Might slightly improve accuracy.
   *
   * @group getParam
   * */
  def getUseContrib: Boolean = $(this.useContrib)

  /** Choose the proportion of training dataset to be validated against the model on each Epoch. The value should be between 0.0 and 1.0 and by default it is 0.0 and off.
   *
   * @group getParam
   * */
  def getValidationSplit: Float = $(this.validationSplit)

  /** whether to include confidence scores in annotation metadata
   *
   * @group getParam
   * */
  def getIncludeConfidence: Boolean = $(includeConfidence)

  /** Whether to output to annotators log folder
   *
   * @group getParam
   * */
  def getEnableOutputLogs: Boolean = $(enableOutputLogs)

  /** Folder path to save training logs
   *
   * @group getParam
   */
  def getOutputLogsPath: String = $(outputLogsPath)

  /** Memory Optimizer
   *
   * @group getParam
   * */
  def getEnableMemoryOptimizer: Boolean = $(this.enableMemoryOptimizer)

  /** Learning Rate
   *
   * @group setParam
   * */
  def setLr(lr: Float): NerDLApproach.this.type = set(this.lr, lr)

  /** Learning rate decay coefficient. Real Learning Rage = lr / (1 + po * epoch)
   *
   * @group setParam
   * */
  def setPo(po: Float): NerDLApproach.this.type = set(this.po, po)

  /** Batch size
   *
   * @group setParam
   * */
  def setBatchSize(batch: Int): NerDLApproach.this.type = set(this.batchSize, batch)

  /** Dropout coefficient
   *
   * @group setParam
   * */
  def setDropout(dropout: Float): NerDLApproach.this.type = set(this.dropout, dropout)

  /** Folder path that contain external graph files
   *
   * @group setParam
   * */
  def setGraphFolder(path: String): NerDLApproach.this.type = set(this.graphFolder, path)

  /** ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()
   *
   * @group setParam
   * */
  def setConfigProtoBytes(bytes: Array[Int]): NerDLApproach.this.type = set(this.configProtoBytes, bytes)

  /** Whether to use contrib LSTM Cells. Not compatible with Windows. Might slightly improve accuracy.
   *
   * @group setParam
   * */
  def setUseContrib(value: Boolean): NerDLApproach.this.type = if (value && SystemUtils.IS_OS_WINDOWS) throw new UnsupportedOperationException("Cannot set contrib in Windows") else set(useContrib, value)

  /** Choose the proportion of training dataset to be validated against the model on each Epoch. The value should be between 0.0 and 1.0 and by default it is 0.0 and off.
   *
   * @group setParam
   * */
  def setValidationSplit(validationSplit: Float): NerDLApproach.this.type = set(this.validationSplit, validationSplit)

  /** Whether logs for validation to be extended: it displays time and evaluation of each label. Default is false.
   *
   * @group setParam
   * */
  def setEvaluationLogExtended(evaluationLogExtended: Boolean): NerDLApproach.this.type = set(this.evaluationLogExtended, evaluationLogExtended)

  /** Whether to output to annotators log folder
   *
   * @group setParam
   * */
  def setEnableOutputLogs(enableOutputLogs: Boolean): NerDLApproach.this.type = set(this.enableOutputLogs, enableOutputLogs)

  /** Folder path to save training logs
   *
   * @group setParam
   */
  def setOutputLogsPath(path: String): NerDLApproach.this.type = set(this.outputLogsPath, path)

  /** Whether to optimize for large datasets or not. Enabling this option can slow down training.
   *
   * @group setParam
   */
  def setEnableMemoryOptimizer(value: Boolean): NerDLApproach.this.type = set(this.enableMemoryOptimizer, value)

  /** Path to test dataset. If set, it is used to calculate statistics on it during training.
   *
   * @group setParam
   * */
  def setTestDataset(path: String,
                     readAs: ReadAs.Format = ReadAs.SPARK,
                     options: Map[String, String] = Map("format" -> "parquet")): this.type =
    set(testDataset, ExternalResource(path, readAs, options))

  /** Path to test dataset. If set, it is used to calculate statistics on it during training.
   *
   * @group setParam
   * */
  def setTestDataset(er: ExternalResource): NerDLApproach.this.type = set(testDataset, er)

  /** Whether to include confidence scores in annotation metadata
   *
   * @group setParam
   * */
  def setIncludeConfidence(value: Boolean): NerDLApproach.this.type = set(this.includeConfidence, value)

  /** whether to include confidence scores for all tags rather than just for the predicted one
   *
   * @group setParam
   * */
  def setIncludeAllConfidenceScores(value: Boolean): this.type = set(this.includeAllConfidenceScores, value)

  setDefault(
    minEpochs -> 0,
    maxEpochs -> 70,
    lr -> 1e-3f,
    po -> 0.005f,
    batchSize -> 8,
    dropout -> 0.5f,
    verbose -> Verbose.Silent.id,
    useContrib -> true,
    validationSplit -> 0.0f,
    evaluationLogExtended -> false,
    includeConfidence -> false,
    includeAllConfidenceScores -> false,
    enableOutputLogs -> false,
    outputLogsPath -> "",
    enableMemoryOptimizer -> false
  )

  override val verboseLevel: Verbose.Level = Verbose($(verbose))

  def calculateEmbeddingsDim(sentences: Seq[WordpieceEmbeddingsSentence]): Int = {
    sentences.find(s => s.tokens.nonEmpty)
      .map(s => s.tokens.head.embeddings.length)
      .getOrElse(1)
  }

  override def beforeTraining(spark: SparkSession): Unit = {
    LoadsContrib.loadContribToCluster(spark)
    LoadsContrib.loadContribToTensorflow()
  }

  override def train(dataset: Dataset[_], recursivePipeline: Option[PipelineModel]): NerDLModel = {

    require($(validationSplit) <= 1f | $(validationSplit) >= 0f, "The validationSplit must be between 0f and 1f")

    val train = dataset.toDF()

    val test = if (!isDefined(testDataset)) {
      train.limit(0) // keep the schema only
    }
    else {
      ResourceHelper.readParquetSparkDataFrame($(testDataset))
    }


    val embeddingsRef = HasStorageRef.getStorageRefFromInput(dataset, $(inputCols), AnnotatorType.WORD_EMBEDDINGS)

    val Array(validSplit, trainSplit) = train.randomSplit(Array($(validationSplit), 1.0f - $(validationSplit)))

    val trainIteratorFunc = getIteratorFunc(trainSplit)
    val validIteratorFunc = getIteratorFunc(validSplit)
    val testIteratorFunc = getIteratorFunc(test)


    val (labels, chars, embeddingsDim, dsLen) = getDataSetParams(trainIteratorFunc())

    val settings = DatasetEncoderParams(labels.toList, chars.toList,
      Array.fill(embeddingsDim)(0f).toList, embeddingsDim)
    val encoder = new NerDatasetEncoder(
      settings
    )

    val graphFile = NerDLApproach.searchForSuitableGraph(labels.size, embeddingsDim, chars.size + 1,
      get(graphFolder))

    val graph = new Graph()
    val graphStream = ResourceHelper.getResourceStream(graphFile)
    val graphBytesDef = IOUtils.toByteArray(graphStream)
    graph.importGraphDef(GraphDef.parseFrom(graphBytesDef))

    val tf = new TensorflowWrapper(Variables(Array.empty[Array[Byte]], Array.empty[Byte]), graph.toGraphDef.toByteArray)

    val ner = try {
      val model = new TensorflowNer(tf, encoder, Verbose($(verbose)))
      if (isDefined(randomSeed)) {
        Random.setSeed($(randomSeed))
      }

      // start the iterator here once again
      model.train(trainIteratorFunc(),
        dsLen,
        validIteratorFunc(),
        (dsLen * $(validationSplit)).toLong,
        $(lr),
        $(po),
        $(dropout),
        $(batchSize),
        graphFileName = graphFile,
        test = testIteratorFunc(),
        endEpoch = $(maxEpochs),
        configProtoBytes = getConfigProtoBytes,
        validationSplit = $(validationSplit),
        evaluationLogExtended = $(evaluationLogExtended),
        includeConfidence = $(includeConfidence),
        enableOutputLogs = $(enableOutputLogs),
        outputLogsPath = $(outputLogsPath),
        uuid = this.uid
      )
      model
    }

    catch {
      case e: Exception =>
        graph.close()
        throw e
    }

    val newWrapper =
      new TensorflowWrapper(
        TensorflowWrapper.extractVariablesSavedModel(tf.getSession(configProtoBytes = getConfigProtoBytes)),
        tf.graph)

    val model = new NerDLModel()
      .setDatasetParams(ner.encoder.params)
      .setModelIfNotSet(dataset.sparkSession, newWrapper)
      .setIncludeConfidence($(includeConfidence))
      .setStorageRef(embeddingsRef)

    if (get(configProtoBytes).isDefined)
      model.setConfigProtoBytes($(configProtoBytes))

    model

  }

  def getDataSetParams(dsIt: Iterator[Array[(TextSentenceLabels, WordpieceEmbeddingsSentence)]]): (mutable.Set[String], mutable.Set[Char], Int, Long) = {

    var labels = scala.collection.mutable.Set[String]()
    var chars = scala.collection.mutable.Set[Char]()
    var embeddingsDim = 1
    var dsLen = 0L

    // try to be frugal with memory and with number of passes thru the iterator
    for (batch <- dsIt) {
      dsLen += batch.length
      for (datapoint <- batch) {

        for (label <- datapoint._1.labels)
          labels += label

        for (token <- datapoint._2.tokens; char <- token.token.toCharArray)
          chars += char

        if (datapoint._2.tokens.nonEmpty)
          embeddingsDim = datapoint._2.tokens.head.embeddings.length
      }
    }

    (labels, chars, embeddingsDim, dsLen)
  }


  def getIteratorFunc(dataset: Dataset[Row]): () => Iterator[Array[(TextSentenceLabels, WordpieceEmbeddingsSentence)]] = {

    if ($(enableMemoryOptimizer)) {
      () => NerTagged.iterateOnDataframe(dataset, getInputCols, $(labelColumn), $(batchSize))

    } else {
      val inMemory = dataset
        .select($(labelColumn), getInputCols.toSeq: _*)
        .collect()

      () => NerTagged.iterateOnArray(inMemory, getInputCols, $(batchSize))

    }
  }

}


trait WithGraphResolver {
  def searchForSuitableGraph(tags: Int, embeddingsNDims: Int, nChars: Int, localGraphPath: Option[String] = None):
  String = {

    val files: Seq[String] = getFiles(localGraphPath)

    // 1. Filter Graphs by embeddings
    val embeddingsFiltered = files.map { filePath =>
      val file = new File(filePath)
      val name = file.getName
      val graphPrefix = "blstm_"

      if (name.startsWith(graphPrefix)) {
        val clean = name.replace(graphPrefix, "").replace(".pb", "")
        val graphParams = clean.split("_").take(4).map(s => s.toInt)
        val Array(fileTags, fileEmbeddingsNDims, _, fileNChars) = graphParams

        if (embeddingsNDims == fileEmbeddingsNDims)
          Some((fileTags, fileEmbeddingsNDims, fileNChars))
        else
          None
      }
      else {
        None
      }
    }

    require(embeddingsFiltered.exists(_.nonEmpty), s"Graph dimensions should be $embeddingsNDims: Could not find a suitable tensorflow graph for embeddings dim: $embeddingsNDims tags: $tags nChars: $nChars. " +
      s"Check https://nlp.johnsnowlabs.com/docs/en/graph for instructions to generate the required graph.")

    // 2. Filter by labels and nChars
    val tagsFiltered = embeddingsFiltered.map {
      case Some((fileTags, fileEmbeddingsNDims, fileNChars)) =>
        if (tags > fileTags)
          None
        else
          Some((fileTags, fileEmbeddingsNDims, fileNChars))
      case _ => None
    }

    require(tagsFiltered.exists(_.nonEmpty), s"Graph tags size should be $tags: Could not find a suitable tensorflow graph for embeddings dim: $embeddingsNDims tags: $tags nChars: $nChars. " +
      s"Check https://nlp.johnsnowlabs.com/docs/en/graph for instructions to generate the required graph.")

    // 3. Filter by labels and nChars
    val charsFiltered = tagsFiltered.map {
      case Some((fileTags, fileEmbeddingsNDims, fileNChars)) =>
        if (nChars > fileNChars)
          None
        else
          Some((fileTags, fileEmbeddingsNDims, fileNChars))
      case _ => None
    }

    require(charsFiltered.exists(_.nonEmpty), s"Graph chars size should be $nChars: Could not find a suitable tensorflow graph for embeddings dim: $embeddingsNDims tags: $tags nChars: $nChars. " +
      s"Check https://nlp.johnsnowlabs.com/docs/en/graph for instructions to generate the required graph")

    for (i <- files.indices) {
      if (charsFiltered(i).nonEmpty)
        return files(i)
    }

    throw new IllegalStateException("Code shouldn't pass here")
  }

  private def getFiles(localGraphPath: Option[String]): Seq[String] = {
    var files: Seq[String] = List()

    if (localGraphPath.isDefined && localGraphPath.get.startsWith("s3://")) {

      val bucketName = localGraphPath.get.substring("s3://".length).split("/").head


      require(bucketName != "", "S3 bucket name is not define. Please define it with parameter setS3BucketName")

      val keyPrefix = localGraphPath.get.substring(("s3://" + bucketName).length + 1)
      var tmpDirectory = SparkFiles.getRootDirectory()

      val awsGateway = new AWSGateway(ConfigLoader.getConfigStringValue(ConfigHelper.awsExternalAccessKeyId),
        ConfigLoader.getConfigStringValue(ConfigHelper.awsExternalSecretAccessKey),
        ConfigLoader.getConfigStringValue(ConfigHelper.awsExternalSessionToken),
        ConfigLoader.getConfigStringValue(ConfigHelper.awsExternalProfileName),
        ConfigLoader.getConfigStringValue(ConfigHelper.awsExternalRegion))

      awsGateway.downloadFilesFromDirectory(bucketName, keyPrefix, new File(tmpDirectory))

      tmpDirectory = tmpDirectory + "/" + keyPrefix
      files = ResourceHelper.listLocalFiles(tmpDirectory).map(_.getAbsolutePath)
    } else {
      files = localGraphPath.map(path =>
        ResourceHelper.listLocalFiles(ResourceHelper.copyToLocal(path)).map(_.getAbsolutePath))
        .getOrElse(ResourceHelper.listResourceDirectory("/ner-dl"))
    }
    files
  }

}

/**
 * This is the companion object of [[NerDLApproach]]. Please refer to that class for the documentation.
 */
object NerDLApproach extends DefaultParamsReadable[NerDLApproach] with WithGraphResolver