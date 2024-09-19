package dynamodbignorenulls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.internal.waiters.ResponseOrException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.IgnoreNullsMode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

public class UpdateItemForNestedComponents {
    public static final String TABLE_NAME = "PersonTable";
    public static final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    public static final DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(dynamoDbClient).build();
    static final DynamoDbEnhancedClient dynamoDbEnhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
    static TableSchema<Person> personTableSchema = TableSchema.fromBean(Person.class);
    static DynamoDbTable<Person> personDynamoDbTable = dynamoDbEnhancedClient.table(TABLE_NAME, personTableSchema);
    private static final Logger logger = LoggerFactory.getLogger(UpdateItemForNestedComponents.class);

    public static void main(String[] args) {

        UpdateItemForNestedComponents ignoreNullsExample = new UpdateItemForNestedComponents();
   //     createTable(personDynamoDbTable, waiter);
        try {
            personDynamoDbTable.deleteItem(r->r.key(k->k.partitionValue(1)).build());
            ignoreNullsExample.updateWithIgnoreNullsTrue();

            personDynamoDbTable.deleteItem(r->r.key(k->k.partitionValue(1)).build());
            ignoreNullsExample.updateWithIgnoreNullsFalse();
        } catch (SdkException e) {
            logger.error(e.getMessage());
        } finally {
//            tearDownOfIntegrationTest(dynamoDbClient, myBeanTable);
        }
    }


    public static void setUpForIntegrationTest(DynamoDbTable<Person> myBeanDynamoDbTable,
                                               DynamoDbWaiter waiter) {
        myBeanDynamoDbTable.createTable();

        ResponseOrException<DescribeTableResponse> response = waiter
            .waitUntilTableExists(builder -> builder.tableName(TABLE_NAME).build())
            .matched();
        DescribeTableResponse tableDescription = response.response().orElseThrow(
            () -> new RuntimeException(TABLE_NAME + " was not created."));
        logger.info("{} was created", TABLE_NAME);
    }

    public static void tearDownOfIntegrationTest(DynamoDbClient dynamoDbClient, DynamoDbTable<Person> personDynamoDbTable) {
        personDynamoDbTable.deleteTable();
        waiter.waitUntilTableNotExists(b -> b.tableName(TABLE_NAME));
        waiter.close();
        logger.info(TABLE_NAME + " table deleted");
    }

    public Map<String, AttributeValue> toAttributeMap(Person person) {
        // Sort and log the TableSchema::itemToMap results for comparison.
        // Use the version of itemToMap that ignores nulls for scalar attributes.
        return personTableSchema.itemToMap(person, true);
    }

    /**
     * When ignoreNulls is true, you can
     *    * provide only the scalar attributes you want to update/add. This includes
     *      scalar attributes that are nested in a bean or map entry, but not list entries.
     *    * Replace beans, maps, and lists.
     *    * Add a new attribute that is a list.
     */
    public void updateWithIgnoreNullsTrue() {
        // Save a person with a mainAddress and a list of hobbies.
        Address mainAddress = new Address();
        mainAddress.setState("CA");
        mainAddress.setCity("LA");
        mainAddress.setZipCode("98765");
        mainAddress.setStreet("123 Main St");

        List<String> hobbies = Arrays.asList("birdwatching", "swimming");

        Person person = new Person();
        person.setId(1);
        person.setFirstName("first name");

        person.setMainAddress(mainAddress);
        person.setHobbies(hobbies);

        // The initial save contains a firstName, mainAddress, and hobbies.
        personDynamoDbTable.putItem(person);

        // Assume the following code runs at a later time.

        // Update the main address' state value.
        Person person1ForUpdate = new Person();
        person1ForUpdate.setId(1); // Partition key must be specified.
        Address addressForUpdate = new Address();
        addressForUpdate.setState("WA");
        person1ForUpdate.setMainAddress(addressForUpdate);

        // Replace the list of hobbies.
        List<String> hobbies2 = Arrays.asList("chess", "pickleball");
        person1ForUpdate.setHobbies(hobbies2);

        PhoneNumber mobilePhone = new PhoneNumber();
        mobilePhone.setNumber("000-000-0000");
        mobilePhone.setType("mobile");
        person1ForUpdate.setPhoneNumbers(Arrays.asList(mobilePhone));


        Person updatedPerson = personDynamoDbTable.updateItem(r -> r
            .item(person1ForUpdate)
      //      .ignoreNulls(Boolean.TRUE) // Retain other values.
            .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
            .build());

        // Scalar attribute updated.
        assert updatedPerson.getMainAddress().getState().equals("WA");
        // Because we set ignoreNulls to true, attribute values are retained even though
        // they were not provided in person1ForUpdate.
        assert updatedPerson.getMainAddress().getCity().equals("LA");
        assert updatedPerson.getFirstName().equals("first name");
        // List of hobbies was replaced.
        assert updatedPerson.getHobbies().contains("pickleball");
        assert updatedPerson.getPhoneNumbers().size() == 1;
        logger.info("Assertions are valid.");
    }

    /**
     * You must set ignoreNulls to false to do the following:
     *    * add a new attribute that is a map or bean
     *    * update list items or add new items to a list.
     * When ignoreNulls is false, you can also update/add top-level
     * scalar attributes and scalar attributes at any level in the object graph
     */
    public void updateWithIgnoreNullsFalse(){
        // Save a person with list of phone numbers
        Person person = new Person();
        person.setId(1);
        person.setFirstName("first name");

        Address mainAddress = new Address();
        mainAddress.setState("First");
        mainAddress.setCity("First");
        mainAddress.setZipCode("First");
        mainAddress.setStreet("First");

        List<PhoneNumber> phoneNumbers = new ArrayList<>();
        PhoneNumber mobilePhone = new PhoneNumber();
        mobilePhone.setNumber("111-111-1111");
        mobilePhone.setType("mobile");
        phoneNumbers.add(mobilePhone);

        person.setPhoneNumbers(phoneNumbers);

        personDynamoDbTable.putItem(person);

        // Assume the following code runs at a later time.

        // Update the list of phone numbers.
        Person person1ForUpdate = new Person();
        person1ForUpdate.setId(1); // Partition key must be specified.

        PhoneNumber mobilePhoneForUpdate = new PhoneNumber();
        mobilePhoneForUpdate.setType("cell");

        List<PhoneNumber> phoneNumbersForUpdate = new ArrayList<>();
        phoneNumbersForUpdate.add(mobilePhoneForUpdate);

        PhoneNumber landLine = new PhoneNumber();
        landLine.setType("home");
        landLine.setNumber("000-000-0000");
        phoneNumbersForUpdate.add(landLine);
        person1ForUpdate.setPhoneNumbers(phoneNumbersForUpdate);

        // Add a new mainAddress.
        Address mainAddressForUpdate = new Address();
        mainAddress.setState("Second");
        mainAddressForUpdate.setCity("Second");
        mainAddressForUpdate.setZipCode("Second");
        mainAddressForUpdate.setStreet("Second");

        person1ForUpdate.setMainAddress(mainAddressForUpdate);

        Person updatedPerson = personDynamoDbTable.updateItem(r->r
                .item(person1ForUpdate)
              //  .ignoreNulls(Boolean.FALSE)
                .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
                .build());

        // The phone number list has been updated.
        assert updatedPerson.getPhoneNumbers().size()  == 2;
        // Nested scalar attribute has been updated from "mobile" to "cell"
        assert updatedPerson.getPhoneNumbers().get(0).getType().equals("cell");
        // First name remains the same.
        assert updatedPerson.getFirstName().equals("first name");
        // New mainAddress bean added that we previously null.
        assert updatedPerson.getMainAddress() != null;
        logger.info("Assertions are valid.");
    }
}
