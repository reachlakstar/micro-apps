package micro.apps.pipeline

import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import com.sksamuel.avro4k.Avro
import java.io.Serializable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import micro.apps.kbeam.map
import micro.apps.model.Person
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.beam.sdk.extensions.gcp.auth.NoopCredentialFactory
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO
import org.apache.beam.sdk.options.PipelineOptionsFactory
import org.apache.beam.sdk.testing.PAssert
import org.apache.beam.sdk.testing.TestPipeline
import org.joda.time.Instant
import org.junit.Rule

class ClassifierPipelineTest : Serializable {

    private val host = "localhost:8085"
    private val projectId = "my-project-id"
    private val inputTopicName = "classifier-input"
    private val outputTopicName = "classifier-output"
    private val subscriptionName = "classifier-input"
    private val helper = Helper(host, projectId)
    private lateinit var testOptions: ClassifierOptions

    @Rule
    @Transient
    @JvmField
    val pipeline = TestPipeline.create()

    @BeforeTest
    @Throws(Exception::class)
    fun setup() {
        PipelineOptionsFactory.register(ClassifierOptions::class.java)
        val args = arrayOf(
            "--windowDuration=310s",
            "--inputTopic=projects/$projectId/topics/$inputTopicName",
            "--inputSubscription=projects/$projectId/subscriptions/$subscriptionName",
            "--outputTopic=projects/$projectId/topics/$outputTopicName")
        testOptions = PipelineOptionsFactory.fromArgs(*args).withValidation().`as`(ClassifierOptions::class.java)
        testOptions.pubsubRootUrl = "http://localhost:8085"
        testOptions.credentialFactoryClass = NoopCredentialFactory::class.java
        testOptions.isBlockOnRun = false
        helper.createTopic(inputTopicName)
        helper.createSubscription(inputTopicName, subscriptionName)
        // seed data
        persons.forEach {
            val data = Avro.default.dump(Person.serializer(), it)
            val pubsubMessage = PubsubMessage.newBuilder()
                .setData(ByteString.copyFrom(data))
                .putAttributes("timestamp", Instant.now().millis.toString()).build()
            helper.sendMessage(inputTopicName, pubsubMessage)
        }
    }

    @AfterTest
    @Throws(Exception::class)
    fun after() {
        helper.deleteSubscription(subscriptionName)
        helper.deleteTopic(inputTopicName)
    }

    @Test @Ignore
    fun runE2ETest() {
        assertEquals("310s", testOptions.windowDuration)

        val schema = Schema.Parser().parse(javaClass.getResourceAsStream("/data/person.avsc"))
        val serializer = Person.serializer()
        val inputs: List<GenericRecord> = persons.map {
            Avro.default.toRecord(serializer, it)
        }

        val read = pipeline.apply("Read data from PubSub",
            PubsubIO.readAvroGenericRecords(schema).fromSubscription(testOptions.inputSubscription)
        ).map {
            println(it)
            it
        }

        PAssert.that(read).containsInAnyOrder(inputs)

        pipeline.run(testOptions)
    }
}