package test;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.github.cdimascio.dotenv.Dotenv;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GigSwapBot extends TelegramLongPollingBot {

    private static final Logger logger = LoggerFactory.getLogger(GigSwapBot.class);

    private static final Dotenv dotenv = Dotenv.load();
    private static final String BOT_TOKEN = dotenv.get("BOT_TOKEN");
    private static final String CONNECTION_STRING = dotenv.get("MONGO_CONNECTION_STRING");
    private static final String DATABASE_NAME = dotenv.get("DATABASE_NAME");
    private static final String COLLECTION_NAME = dotenv.get("COLLECTION_NAME");
    private static final String REVIEW_COLLECTION_NAME = dotenv.get("REVIEW_COLLECTION_NAME");

    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> collection;
    private static MongoCollection<Document> reviewCollection;

    private Map<Long, String> userStates = new HashMap<>();
    private Map<Long, Map<String, String>> userListings = new HashMap<>();
    private Map<Long, Long> activeChats = new ConcurrentHashMap<>();
    private Map<Long, Queue<Long>> chatQueues = new ConcurrentHashMap<>();
    private Map<Long, List<Document>> filteredListings = new HashMap<>();

    static {
        try {
            logger.info("Connecting to MongoDB with connection string: {}", CONNECTION_STRING);
            ConnectionString connString = new ConnectionString(CONNECTION_STRING);
            ServerApi serverApi = ServerApi.builder()
                    .version(ServerApiVersion.V1)
                    .build();
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connString)
                    .serverApi(serverApi)
                    .applyToClusterSettings(builder -> builder.serverSelectionTimeout(60000, TimeUnit.MILLISECONDS))
                    .build();
            mongoClient = MongoClients.create(settings);
            database = mongoClient.getDatabase(DATABASE_NAME);
            collection = database.getCollection(COLLECTION_NAME);
            reviewCollection = database.getCollection(REVIEW_COLLECTION_NAME);
            logger.info("MongoClient created successfully");
        } catch (Exception e) {
            logger.error("Error connecting to MongoDB: ", e);
        }
    }

    public GigSwapBot() {
        clearWebhook();
    }

    public void clearWebhook() {
        try {
            DeleteWebhook deleteWebhook = new DeleteWebhook();
            execute(deleteWebhook);
        } catch (TelegramApiException e) {
            logger.warn("No existing webhook to clear or error clearing webhook: ", e);
        }
    }

    @Override
    public String getBotUsername() {
        return "GigSwapBot"; // Replace with your bot's username
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String messageText = update.getMessage().getText();

            if (messageText.equals("/endchat")) {
                endChat(chatId);
                return;
            }

            if (activeChats.containsKey(chatId)) {
                forwardMessage(chatId, messageText);
                return;
            }

            if (messageText.startsWith("/start ")) {
                String uniqueId = messageText.split(" ")[1];
                handleStartWithLink(chatId, uniqueId);
                return;
            }

            switch (messageText.split(" ")[0]) {
                case "/start":
                    sendStartMessageWithButtons(chatId);
                    break;
                case "/sell":
                    userStates.put(chatId, "AWAITING_EVENT_NAME");
                    userListings.put(chatId, new HashMap<>());
                    sendResponse(chatId, "Please enter the event name:");
                    break;
                case "/buy":
                    listAvailableTickets(chatId, 0);
                    break;
                case "/mylistings":
                    listUserTickets(chatId, 0);
                    break;
                case "/delete":
                    listUserTicketsForDeletion(chatId, 0);
                    break;
                default:
                    handleUserInput(chatId, messageText);
                    break;
            }
        } else if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery().getMessage().getChatId(), update.getCallbackQuery().getData());
        }
    }

    private void sendStartMessageWithButtons(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Hello! I am a bot that can help you buy and sell tickets.");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> rowInline = new ArrayList<>();

        InlineKeyboardButton buyButton = new InlineKeyboardButton();
        buyButton.setText("Buy");
        buyButton.setCallbackData("buy");

        InlineKeyboardButton sellButton = new InlineKeyboardButton();
        sellButton.setText("Sell");
        sellButton.setCallbackData("sell");

        rowInline.add(buyButton);
        rowInline.add(sellButton);

        rowsInline.add(rowInline);
        markupInline.setKeyboard(rowsInline);

        message.setReplyMarkup(markupInline);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error sending message: ", e);
        }
    }

    private void handleCallback(long chatId, String callbackData) {
        if (callbackData.equals("buy")) {
            listAvailableTickets(chatId, 0);
        } else if (callbackData.equals("sell")) {
            userStates.put(chatId, "AWAITING_EVENT_NAME");
            userListings.put(chatId, new HashMap<>());
            sendResponse(chatId, "Please enter the event name:");
        } else if (callbackData.equals("filter")) {
            sendResponse(chatId, "Please enter the event name to filter by:");
            userStates.put(chatId, "AWAITING_FILTER_EVENT_NAME");
        } else if (callbackData.startsWith("page_")) {
            int page = Integer.parseInt(callbackData.split("_")[1]);
            listAvailableTickets(chatId, page);
        } else if (callbackData.startsWith("mypage_")) {
            int page = Integer.parseInt(callbackData.split("_")[1]);
            listUserTickets(chatId, page);
        } else if (callbackData.startsWith("delpage_")) {
            int page = Integer.parseInt(callbackData.split("_")[1]);
            listUserTicketsForDeletion(chatId, page);
        } else if (callbackData.startsWith("delete_")) {
            deleteListing(chatId, callbackData.split("_")[1]);
        } else if (callbackData.equals("purchase")) {
            sendResponse(chatId, "Which listing number are you interested in?");
            userStates.put(chatId, "AWAITING_PURCHASE_LISTING");
        } else if (callbackData.equals("share")) {
            sendResponse(chatId, "Which listing number would you like to share?");
            userStates.put(chatId, "AWAITING_SHARE_LISTING_NUMBER");
        } else if (callbackData.equals("view_reviews")) {
            sendResponse(chatId, "Which listing number would you like to view reviews for?");
            userStates.put(chatId, "AWAITING_VIEW_REVIEW_LISTING_NUMBER");
        } else if (callbackData.startsWith("purchase_")) {
            String uniqueId = callbackData.split("_")[1];
            handlePurchaseWithLink(chatId, uniqueId);
        } else if (callbackData.startsWith("reviews_")) {
            String sellerChatId = callbackData.split("_")[1];
            int page = Integer.parseInt(callbackData.split("_")[2]);
            displayReviews(chatId, sellerChatId, page);
        } else if (callbackData.startsWith("leave_review_yes_")) {
            String[] parts = callbackData.split("_");
            long sellerChatId = Long.parseLong(parts[3]);
            userStates.put(chatId, "AWAITING_REVIEW_" + sellerChatId);
            sendResponse(chatId, "Please leave a review for the seller (1-5 stars):");
        } else if (callbackData.equals("leave_review_no")) {
            sendResponse(chatId, "Thank you! Have a great day.");
        }
    }

    private void handleUserInput(long chatId, String messageText) {
        String state = userStates.get(chatId);

        if (state == null) {
            sendResponse(chatId, "Please use /sell to start a new listing or /buy to view available listings.");
            return;
        }

        Map<String, String> listing = userListings.get(chatId);

        if (state.startsWith("AWAITING_REVIEW_")) {
            long sellerChatId = Long.parseLong(state.split("_")[2]);
            int rating;
            try {
                rating = Integer.parseInt(messageText.trim());
                if (rating < 1 || rating > 5) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                sendResponse(chatId, "Invalid rating. Please enter a number between 1 and 5.");
                return;
            }
            saveReviewToDatabase(chatId, sellerChatId, rating);
            sendResponse(chatId, "Thank you for your review!");
            userStates.remove(chatId);
            return;
        }

        switch (state) {
            case "AWAITING_EVENT_NAME":
                listing.put("eventName", messageText);
                userStates.put(chatId, "AWAITING_QUANTITY");
                sendResponse(chatId, "How many tickets do you have?");
                break;
            case "AWAITING_QUANTITY":
                listing.put("quantity", messageText);
                userStates.put(chatId, "AWAITING_EVENT_DATE");
                sendResponse(chatId, "What is the event date? (e.g., 31-12-2024)");
                break;
            case "AWAITING_EVENT_DATE":
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy");
                    sdf.setLenient(false);
                    sdf.parse(messageText);  // Just parse to validate the format
                    listing.put("eventDate", messageText);
                    userStates.put(chatId, "AWAITING_LOCATION");
                    sendResponse(chatId, "Where is the event located?");
                } catch (ParseException e) {
                    sendResponse(chatId, "Invalid date format. Please enter the date in the format dd-MM-yyyy (e.g., 31-12-2024).");
                }
                break;
            case "AWAITING_LOCATION":
                listing.put("location", messageText);
                userStates.put(chatId, "AWAITING_CATEGORY");
                sendResponse(chatId, "What ticket category is it? (e.g., General standing / Cat 2)");
                break;
            case "AWAITING_CATEGORY":
                listing.put("category", messageText);
                userStates.put(chatId, "AWAITING_PRICE");
                sendResponse(chatId, "What is the price per ticket?");
                break;
            case "AWAITING_PRICE":
                listing.put("price", messageText);
                saveListingToDatabase(chatId, listing);
                sendResponse(chatId, "Thanks! Your listing has been saved.");
                userStates.remove(chatId);
                userListings.remove(chatId);
                break;
            case "AWAITING_FILTER_EVENT_NAME":
                filterTicketsByEventName(chatId, messageText, 0);
                userStates.remove(chatId);
                break;
            case "AWAITING_PURCHASE_LISTING":
                initiatePurchase(chatId, Integer.parseInt(messageText));
                break;
            case "AWAITING_SHARE_LISTING_NUMBER":
                generateShareableLink(chatId, Integer.parseInt(messageText));
                userStates.remove(chatId);
                break;
            case "AWAITING_VIEW_REVIEW_LISTING_NUMBER":
                displayReviews(chatId, Integer.parseInt(messageText), 0);
                userStates.remove(chatId);
                break;
            default:
                sendResponse(chatId, "Unknown state. Please start again.");
                userStates.remove(chatId);
                userListings.remove(chatId);
                break;
        }
    }

    private void saveListingToDatabase(long chatId, Map<String, String> listing) {
        String dt_string = new SimpleDateFormat("dd-MM-yyyy").format(new Date());
        String uniqueId = UUID.randomUUID().toString();
        Document doc = new Document("chatId", chatId)
                .append("eventName", listing.get("eventName"))
                .append("quantity", listing.get("quantity"))
                .append("eventDate", listing.get("eventDate"))
                .append("location", listing.get("location"))
                .append("category", listing.get("category"))
                .append("price", listing.get("price"))
                .append("uniqueId", uniqueId)
                .append("LAST_UPDATE", dt_string);
        collection.insertOne(doc);
    }

    private void listAvailableTickets(long chatId, int page) {
        int itemsPerPage = 10;
        Iterable<Document> documents = collection.find()
                .skip(page * itemsPerPage)
                .limit(itemsPerPage);
        long totalDocuments = collection.countDocuments();
        int totalPages = (int) Math.ceil((double) totalDocuments / itemsPerPage);

        List<Document> documentList = new ArrayList<>();
        documents.forEach(documentList::add);
        filteredListings.put(chatId, documentList);

        StringBuilder response = new StringBuilder("Available tickets:\n\n");
        int index = 1;
        for (Document doc : documentList) {
            response.append(index++).append(".\n")
                    .append("Event Name: ").append(doc.getString("eventName")).append("\n")
                    .append("Quantity: ").append(doc.getString("quantity")).append("\n")
                    .append("Event Date: ").append(doc.getString("eventDate")).append("\n")
                    .append("Location: ").append(doc.getString("location")).append("\n")
                    .append("Category: ").append(doc.getString("category")).append("\n")
                    .append("Price: ").append(doc.getString("price")).append("\n\n");
        }
        if (response.toString().equals("Available tickets:\n\n")) {
            response = new StringBuilder("No tickets are currently available for sale.");
        }
        sendResponseWithPageButtons(chatId, response.toString(), page, totalPages, "page_");
    }

    private void listUserTickets(long chatId, int page) {
        int itemsPerPage = 10;
        Iterable<Document> documents = collection.find(new Document("chatId", chatId))
                .skip(page * itemsPerPage)
                .limit(itemsPerPage);
        long totalDocuments = collection.countDocuments(new Document("chatId", chatId));
        int totalPages = (int) Math.ceil((double) totalDocuments / itemsPerPage);

        StringBuilder response = new StringBuilder("Your listings:\n\n");
        int index = 1 + (page * itemsPerPage);
        for (Document doc : documents) {
            response.append(index++).append(".\n")
                    .append("Event Name: ").append(doc.getString("eventName")).append("\n")
                    .append("Quantity: ").append(doc.getString("quantity")).append("\n")
                    .append("Event Date: ").append(doc.getString("eventDate")).append("\n")
                    .append("Location: ").append(doc.getString("location")).append("\n")
                    .append("Category: ").append(doc.getString("category")).append("\n")
                    .append("Price: ").append(doc.getString("price")).append("\n\n");
        }
        if (response.toString().equals("Your listings:\n\n")) {
            response = new StringBuilder("You have no listings.");
        }
        sendResponseWithPageButtons(chatId, response.toString(), page, totalPages, "mypage_", false);
    }

    private void listUserTicketsForDeletion(long chatId, int page) {
        int itemsPerPage = 10;
        Iterable<Document> documents = collection.find(new Document("chatId", chatId))
                .skip(page * itemsPerPage)
                .limit(itemsPerPage);
        long totalDocuments = collection.countDocuments(new Document("chatId", chatId));
        int totalPages = (int) Math.ceil((double) totalDocuments / itemsPerPage);

        StringBuilder response = new StringBuilder("Your listings:\n\n");
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        int index = 1 + (page * itemsPerPage);
        for (Document doc : documents) {
            String eventName = doc.getString("eventName");
            String listingId = doc.getObjectId("_id").toString();
            response.append(index++).append(".\n")
                    .append("Event Name: ").append(eventName).append("\n")
                    .append("Quantity: ").append(doc.getString("quantity")).append("\n")
                    .append("Event Date: ").append(doc.getString("eventDate")).append("\n")
                    .append("Location: ").append(doc.getString("location")).append("\n")
                    .append("Category: ").append(doc.getString("category")).append("\n")
                    .append("Price: ").append(doc.getString("price")).append("\n\n");

            List<InlineKeyboardButton> rowInline = new ArrayList<>();
            InlineKeyboardButton deleteButton = new InlineKeyboardButton();
            deleteButton.setText("Delete Listing " + (index - 1));
            deleteButton.setCallbackData("delete_" + listingId);
            rowInline.add(deleteButton);
            rowsInline.add(rowInline);
        }
        if (response.toString().equals("Your listings:\n\n")) {
            response = new StringBuilder("You have no listings.");
        }
        addPaginationButtons(rowsInline, page, totalPages, "delpage_");

        markupInline.setKeyboard(rowsInline);
        sendResponseWithMarkup(chatId, response.toString(), markupInline);
    }

    private void filterTicketsByEventName(long chatId, String eventName, int page) {
        int itemsPerPage = 10;
        Iterable<Document> documents = collection.find(new Document("eventName", new Document("$regex", eventName).append("$options", "i")))
                .skip(page * itemsPerPage)
                .limit(itemsPerPage);
        long totalDocuments = collection.countDocuments(new Document("eventName", new Document("$regex", eventName).append("$options", "i")));
        int totalPages = (int) Math.ceil((double) totalDocuments / itemsPerPage);

        List<Document> documentList = new ArrayList<>();
        documents.forEach(documentList::add);
        filteredListings.put(chatId, documentList);

        StringBuilder response = new StringBuilder("Filtered tickets:\n\n");
        int index = 1;
        for (Document doc : documentList) {
            response.append(index++).append(".\n")
                    .append("Event Name: ").append(doc.getString("eventName")).append("\n")
                    .append("Quantity: ").append(doc.getString("quantity")).append("\n")
                    .append("Event Date: ").append(doc.getString("eventDate")).append("\n")
                    .append("Location: ").append(doc.getString("location")).append("\n")
                    .append("Category: ").append(doc.getString("category")).append("\n")
                    .append("Price: ").append(doc.getString("price")).append("\n\n");
        }
        if (response.toString().equals("Filtered tickets:\n\n")) {
            response = new StringBuilder("No tickets found for the specified event.");
        }
        sendResponseWithPageButtons(chatId, response.toString(), page, totalPages, "filterpage_");
    }

    private void sendResponseWithPageButtons(long chatId, String text, int currentPage, int totalPages, String callbackPrefix) {
        sendResponseWithPageButtons(chatId, text, currentPage, totalPages, callbackPrefix, true);
    }

    private void sendResponseWithPageButtons(long chatId, String text, int currentPage, int totalPages, String callbackPrefix, boolean includePurchaseButton) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Page buttons
        for (int i = 0; i < totalPages; i++) {
            List<InlineKeyboardButton> rowInline = new ArrayList<>();
            InlineKeyboardButton pageButton = new InlineKeyboardButton();
            pageButton.setText("Page " + (i + 1));
            pageButton.setCallbackData(callbackPrefix + i);
            rowInline.add(pageButton);
            rowsInline.add(rowInline);
        }

        if (includePurchaseButton) {
            InlineKeyboardButton filterButton = new InlineKeyboardButton();
            filterButton.setText("Filter by Event Name");
            filterButton.setCallbackData("filter");
            List<InlineKeyboardButton> rowInline = new ArrayList<>();
            rowInline.add(filterButton);
            rowsInline.add(rowInline);

            InlineKeyboardButton purchaseButton = new InlineKeyboardButton();
            purchaseButton.setText("Purchase ticket/s");
            purchaseButton.setCallbackData("purchase");
            rowInline = new ArrayList<>();
            rowInline.add(purchaseButton);
            rowsInline.add(rowInline);

            // Add the Share Listing button
            InlineKeyboardButton shareButton = new InlineKeyboardButton();
            shareButton.setText("Share Listing");
            shareButton.setCallbackData("share");
            rowInline = new ArrayList<>();
            rowInline.add(shareButton);
            rowsInline.add(rowInline);

            // Add the View Reviews button
            InlineKeyboardButton viewReviewsButton = new InlineKeyboardButton();
            viewReviewsButton.setText("View Reviews");
            viewReviewsButton.setCallbackData("view_reviews");
            rowInline = new ArrayList<>();
            rowInline.add(viewReviewsButton);
            rowsInline.add(rowInline);
        }

        markupInline.setKeyboard(rowsInline);
        sendResponseWithMarkup(chatId, text, markupInline);
    }

    private void addPaginationButtons(List<List<InlineKeyboardButton>> rowsInline, int currentPage, int totalPages, String callbackPrefix) {
        // Add page buttons
        for (int i = 0; i < totalPages; i++) {
            InlineKeyboardButton pageButton = new InlineKeyboardButton();
            pageButton.setText("Page " + (i + 1));
            pageButton.setCallbackData(callbackPrefix + i);
            rowsInline.add(Arrays.asList(pageButton));
        }
    }

    private void sendResponse(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error sending message: ", e);
        }
    }

    private void sendResponseWithMarkup(long chatId, String text, InlineKeyboardMarkup markupInline) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(markupInline);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error sending message: ", e);
        }
    }

    private void deleteListing(long chatId, String listingId) {
        collection.deleteOne(new Document("_id", new org.bson.types.ObjectId(listingId)));
        sendResponse(chatId, "Listing deleted successfully.");
    }

    private void initiatePurchase(long chatId, int listingNumber) {
        List<Document> listings = filteredListings.get(chatId);
        if (listings == null || listingNumber < 1 || listingNumber > listings.size()) {
            sendResponse(chatId, "Invalid listing number. Please try again.");
            return;
        }

        Document listing = listings.get(listingNumber - 1);
        long sellerChatId = listing.getLong("chatId");
        if (activeChats.containsKey(sellerChatId)) {
            sendResponse(chatId, "The seller is currently in a chat with another buyer. You have been added to the queue.");
            chatQueues.putIfAbsent(sellerChatId, new LinkedList<>());
            chatQueues.get(sellerChatId).offer(chatId);
            return;
        }

        activeChats.put(chatId, sellerChatId);
        activeChats.put(sellerChatId, chatId);

        sendResponse(chatId, "You are now connected with the seller. Use /endchat to end the chat.");
        sendResponse(sellerChatId, "Buyer is interested in:\n\n"
                + "Event Name: " + listing.getString("eventName") + "\n"
                + "Quantity: " + listing.getString("quantity") + "\n"
                + "Event Date: " + listing.getString("eventDate") + "\n"
                + "Location: " + listing.getString("location") + "\n"
                + "Category: " + listing.getString("category") + "\n"
                + "Price: " + listing.getString("price") + "\n\n"
                + "You are now connected with the buyer. Use /endchat to end the chat.");
    }

    private void generateShareableLink(long chatId, int listingNumber) {
        List<Document> listings = filteredListings.get(chatId);
        if (listings == null || listingNumber < 1 || listingNumber > listings.size()) {
            sendResponse(chatId, "Invalid listing number. Please try again.");
            return;
        }

        Document listing = listings.get(listingNumber - 1);
        String uniqueId = listing.getString("uniqueId");
        String shareableLink = "https://t.me/GigSwapBot?start=" + uniqueId;

        sendResponse(chatId, "Here is the shareable link for the listing:\n" + shareableLink);
    }

    private void handleStartWithLink(long chatId, String uniqueId) {
        Document listing = collection.find(new Document("uniqueId", uniqueId)).first();
        if (listing == null) {
            sendResponse(chatId, "Listing not found.");
            return;
        }

        StringBuilder response = new StringBuilder("Listing Details:\n\n");
        response.append("Event Name: ").append(listing.getString("eventName")).append("\n")
                .append("Quantity: ").append(listing.getString("quantity")).append("\n")
                .append("Event Date: ").append(listing.getString("eventDate")).append("\n")
                .append("Location: ").append(listing.getString("location")).append("\n")
                .append("Category: ").append(listing.getString("category")).append("\n")
                .append("Price: ").append(listing.getString("price")).append("\n\n");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> rowInline = new ArrayList<>();

        InlineKeyboardButton purchaseButton = new InlineKeyboardButton();
        purchaseButton.setText("Purchase ticket/s");
        purchaseButton.setCallbackData("purchase_" + uniqueId);
        rowInline.add(purchaseButton);
        rowsInline.add(rowInline);
        markupInline.setKeyboard(rowsInline);

        sendResponseWithMarkup(chatId, response.toString(), markupInline);
    }

    private void handlePurchaseWithLink(long chatId, String uniqueId) {
        Document listing = collection.find(new Document("uniqueId", uniqueId)).first();
        if (listing == null) {
            sendResponse(chatId, "Listing not found.");
            return;
        }

        long sellerChatId = listing.getLong("chatId");
        if (activeChats.containsKey(sellerChatId)) {
            sendResponse(chatId, "The seller is currently in a chat with another buyer. You have been added to the queue.");
            chatQueues.putIfAbsent(sellerChatId, new LinkedList<>());
            chatQueues.get(sellerChatId).offer(chatId);
            return;
        }

        activeChats.put(chatId, sellerChatId);
        activeChats.put(sellerChatId, chatId);

        sendResponse(chatId, "You are now connected with the seller. Use /endchat to end the chat.");
        sendResponse(sellerChatId, "Buyer is interested in:\n\n"
                + "Event Name: " + listing.getString("eventName") + "\n"
                + "Quantity: " + listing.getString("quantity") + "\n"
                + "Event Date: " + listing.getString("eventDate") + "\n"
                + "Location: " + listing.getString("location") + "\n"
                + "Category: " + listing.getString("category") + "\n"
                + "Price: " + listing.getString("price") + "\n\n"
                + "You are now connected with the buyer. Use /endchat to end the chat.");
    }

    private void forwardMessage(long chatId, String messageText) {
        Long recipientChatId = activeChats.get(chatId);
        if (recipientChatId != null) {
            sendResponse(recipientChatId, messageText);
        } else {
            sendResponse(chatId, "You are not in an active chat.");
        }
    }

    private void endChat(long chatId) {
        Long otherChatId = activeChats.get(chatId);
        if (otherChatId == null) {
            sendResponse(chatId, "You are not in a chat.");
            return;
        }

        logger.info("Ending chat between {} and {}", chatId, otherChatId);

        activeChats.remove(chatId);
        activeChats.remove(otherChatId);

        logger.info("Removed chat from activeChats map");

        sendResponse(chatId, "Chat ended.");
        sendResponse(otherChatId, "The buyer/seller has ended the chat.");

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> rowInline = new ArrayList<>();

        InlineKeyboardButton yesButton = new InlineKeyboardButton();
        yesButton.setText("Yes");
        yesButton.setCallbackData("leave_review_yes_" + otherChatId);
        rowInline.add(yesButton);

        InlineKeyboardButton noButton = new InlineKeyboardButton();
        noButton.setText("No");
        noButton.setCallbackData("leave_review_no");
        rowInline.add(noButton);

        rowsInline.add(rowInline);
        markupInline.setKeyboard(rowsInline);

        sendResponseWithMarkup(chatId, "Would you like to leave a review for the seller?", markupInline);

        Queue<Long> queue = chatQueues.get(otherChatId);
        if (queue != null && !queue.isEmpty()) {
            long nextBuyerId = queue.poll();
            activeChats.put(otherChatId, nextBuyerId);
            activeChats.put(nextBuyerId, otherChatId);
            sendResponse(otherChatId, "You are now connected with the next buyer in the queue.");
            sendResponse(nextBuyerId, "You are now connected with the seller. Use /endchat to end the chat.");
            logger.info("Connected {} with {}", nextBuyerId, otherChatId);
        } else {
            chatQueues.remove(otherChatId);
            logger.info("No more buyers in queue for {}", otherChatId);
        }
    }

    private void saveReviewToDatabase(long buyerChatId, long sellerChatId, int rating) {
        String reviewId = UUID.randomUUID().toString();
        Document reviewDoc = new Document("reviewId", reviewId)
                .append("buyerChatId", buyerChatId)
                .append("sellerChatId", sellerChatId)
                .append("rating", rating)
                .append("timestamp", new Date());
        reviewCollection.insertOne(reviewDoc);
    }

    private void displayReviews(long chatId, int listingNumber, int page) {
        List<Document> listings = filteredListings.get(chatId);
        if (listings == null || listingNumber < 1 || listingNumber > listings.size()) {
            sendResponse(chatId, "Invalid listing number. Please try again.");
            return;
        }

        Document listing = listings.get(listingNumber - 1);
        long sellerChatId = listing.getLong("chatId");
        displayReviews(chatId, String.valueOf(sellerChatId), page);
    }

    private void displayReviews(long chatId, String sellerChatId, int page) {
        int itemsPerPage = 10;
        Iterable<Document> reviews = reviewCollection.find(new Document("sellerChatId", Long.parseLong(sellerChatId)))
                .skip(page * itemsPerPage)
                .limit(itemsPerPage);
        long totalDocuments = reviewCollection.countDocuments(new Document("sellerChatId", Long.parseLong(sellerChatId)));
        int totalPages = (int) Math.ceil((double) totalDocuments / itemsPerPage);

        double totalRating = 0;
        int reviewCount = 0;
        for (Document review : reviews) {
            totalRating += review.getInteger("rating");
            reviewCount++;
        }

        double averageRating = (reviewCount == 0) ? 0 : totalRating / reviewCount;

        StringBuilder response = new StringBuilder("Seller Reviews:\n\n");
        response.append("Average Rating: ").append(String.format("%.2f", averageRating)).append(" stars\n\n");
        int index = 1 + (page * itemsPerPage);
        for (Document review : reviews) {
            response.append(index++).append(".\n")
                    .append("Rating: ").append(review.getInteger("rating")).append(" stars\n")
                    .append("Date: ").append(review.getDate("timestamp")).append("\n\n");
        }
        if (response.toString().equals("Seller Reviews:\n\n")) {
            response = new StringBuilder("No reviews found for this seller.");
        }
        sendResponseWithReviewPageButtons(chatId, response.toString(), page, totalPages, "reviews_" + sellerChatId + "_");
    }

    private void sendResponseWithReviewPageButtons(long chatId, String text, int currentPage, int totalPages, String callbackPrefix) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Page buttons
        for (int i = 0; i < totalPages; i++) {
            List<InlineKeyboardButton> rowInline = new ArrayList<>();
            InlineKeyboardButton pageButton = new InlineKeyboardButton();
            pageButton.setText("Page " + (i + 1));
            pageButton.setCallbackData(callbackPrefix + i);
            rowInline.add(pageButton);
            rowsInline.add(rowInline);
        }

        markupInline.setKeyboard(rowsInline);
        sendResponseWithMarkup(chatId, text, markupInline);
    }

    public static void main(String[] args) {
        try {
            logger.info("Initializing Database...");
            // Database and collection are already initialized in the static block
            logger.info("Bot Started...");
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new GigSwapBot());
        } catch (TelegramApiException e) {
            logger.error("Error initializing bot: ", e);
        }
    }
}
