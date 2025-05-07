package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;



import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class DiabetesBot extends TelegramLongPollingBot {
    private static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    private static final String BOT_USERNAME = "@DiabetesBot";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // Состояния для разговора
    private static final String SUGAR = "SUGAR";
    private static final String CARBS = "CARBS";
    private static final String INSULIN = "INSULIN";
    private static final String DATE_TIME = "DATE_TIME";
    private static final String CONFIRM_TIME = "CONFIRM_TIME";
    private static final String CONFIRM_DATA = "CONFIRM_DATA";

    // Хранилище состояний и данных пользователей
    private final Map<Long, String> userStates = new HashMap<>();
    private final Map<Long, Map<String, String>> userData = new HashMap<>();

    public DiabetesBot() {
        initDb();
    }

    @Override
    public String getBotUsername() {
        return BOT_USERNAME;
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;
    }

    // Инициализация базы данных
    private void initDb() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:diabetes_data.db")) {
            String sql = "CREATE TABLE IF NOT EXISTS records (" +
                    "chat_id INTEGER, " +
                    "date TEXT, " +
                    "time TEXT, " +
                    "sugar REAL, " +
                    "carbs REAL, " +
                    "insulin TEXT)";
            Statement stmt = conn.createStatement();
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText();

        try {
            if (messageText.equals("/start")) {
                sendMessage(chatId, "Привет! Я бот для диабетиков. Используй /add для ввода данных или /report для отчета.", new ReplyKeyboardRemove());
            } else if (messageText.equals("/add")) {
                userStates.put(chatId, SUGAR);
                userData.putIfAbsent(chatId, new HashMap<>());
                sendMessage(chatId, "Введите уровень сахара (или 0, если не измеряли):", new ReplyKeyboardRemove());
            } else if (messageText.equals("/cancel")) {
                userStates.remove(chatId);
                userData.remove(chatId);
                sendMessage(chatId, "Ввод отменен.", new ReplyKeyboardRemove());
            } else if (userStates.containsKey(chatId)) {
                handleConversation(chatId, messageText);
            } else if (messageText.equals("/report")) {
                generateReport(chatId);
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleConversation(long chatId, String messageText) throws TelegramApiException {
        String state = userStates.get(chatId);
        Map<String, String> data = userData.get(chatId);

        switch (state) {
            case SUGAR:
                try {
                    Double.parseDouble(messageText);
                    data.put("sugar", messageText);
                    userStates.put(chatId, CARBS);
                    sendMessage(chatId, "Введите количество хлебных единиц (или 0, если не ели):", new ReplyKeyboardRemove());
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Пожалуйста, введите число (например, 5.5 или 0).", new ReplyKeyboardRemove());
                }
                break;

            case CARBS:
                try {
                    Double.parseDouble(messageText);
                    data.put("carbs", messageText);
                    userStates.put(chatId, INSULIN);
                    sendMessage(chatId, "Введите количество инсулина (или 0 / -, если не кололи):", new ReplyKeyboardRemove());
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Пожалуйста, введите число (например, 2.0 или 0).", new ReplyKeyboardRemove());
                }
                break;

            case INSULIN:
                if (messageText.equals("-") || messageText.replace(".", "").matches("\\d+\\.?\\d*")) {
                    data.put("insulin", messageText);
                    userStates.put(chatId, DATE_TIME);
                    ReplyKeyboardMarkup keyboard = createKeyboard(List.of("Сейчас", "Ввести вручную"));
                    sendMessage(chatId, "Выберите время записи:", keyboard);
                } else {
                    sendMessage(chatId, "Введите число, 0 или \"-\".", new ReplyKeyboardRemove());
                }
                break;

            case DATE_TIME:
                if (messageText.equals("Сейчас")) {
                    LocalDateTime now = LocalDateTime.now();
                    data.put("date", now.format(DATE_TIME_FORMATTER).split(" ")[0]);
                    data.put("time", now.format(DATE_TIME_FORMATTER).split(" ")[1]);
                    userStates.put(chatId, CONFIRM_DATA);
                    sendConfirmMessage(chatId, data);
                } else if (messageText.equals("Ввести вручную")) {
                    userStates.put(chatId, CONFIRM_TIME);
                    sendMessage(chatId, "Введите дату и время в формате ДД.ММ.ГГГГ ЧЧ:ММ (например, 07.05.2025 14:30):",
                            new ReplyKeyboardRemove());
                } else {
                    sendMessage(chatId, "Выберите \"Сейчас\" или \"Ввести вручную\".", new ReplyKeyboardRemove());
                }
                break;

            case CONFIRM_TIME:
                try {
                    LocalDateTime dateTime = LocalDateTime.parse(messageText, DATE_TIME_FORMATTER);
                    data.put("date", dateTime.format(DATE_TIME_FORMATTER).split(" ")[0]);
                    data.put("time", dateTime.format(DATE_TIME_FORMATTER).split(" ")[1]);
                    userStates.put(chatId, CONFIRM_DATA);
                    sendConfirmMessage(chatId, data);
                } catch (DateTimeParseException e) {
                    sendMessage(chatId, "Неверный формат. Введите в формате ДД.ММ.ГГГГ ЧЧ:ММ.", new ReplyKeyboardRemove());
                }
                break;

            case CONFIRM_DATA:
                if (messageText.equals("Да")) {
                    saveData(chatId, data);
                    sendMessage(chatId, "Данные сохранены!", new ReplyKeyboardRemove());
                    userStates.remove(chatId);
                    userData.remove(chatId);
                } else if (messageText.equals("Нет")) {
                    sendMessage(chatId, "Ввод отменен.", new ReplyKeyboardRemove());
                    userStates.remove(chatId);
                    userData.remove(chatId);
                } else {
                    sendMessage(chatId, "Выберите \"Да\" или \"Нет\".", new ReplyKeyboardRemove());
                }
                break;
        }
    }

    private void sendConfirmMessage(long chatId, Map<String, String> data) throws TelegramApiException {
        String message = String.format(
                "Проверьте данные:\nСахар: %s\nХлебные единицы: %s\nИнсулин: %s\nДата: %s\nВремя: %s\nПодтвердить? (Да/Нет)",
                data.get("sugar"), data.get("carbs"), data.get("insulin"), data.get("date"), data.get("time"));
        ReplyKeyboardMarkup keyboard = createKeyboard(List.of("Да", "Нет"));
        sendMessage(chatId, message, keyboard);
    }

    private void saveData(long chatId, Map<String, String> data) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:diabetes_data.db")) {
            String sql = "INSERT INTO records (chat_id, date, time, sugar, carbs, insulin) VALUES (?, ?, ?, ?, ?, ?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, chatId);
            pstmt.setString(2, data.get("date"));
            pstmt.setString(3, data.get("time"));
            pstmt.setDouble(4, Double.parseDouble(data.get("sugar")));
            pstmt.setDouble(5, Double.parseDouble(data.get("carbs")));
            pstmt.setString(6, data.get("insulin"));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void generateReport(long chatId) throws TelegramApiException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:diabetes_data.db")) {
            String sql = "SELECT date, time, sugar, carbs, insulin FROM records WHERE chat_id = ?";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();

            Map<String, List<Record>> recordsByDate = new HashMap<>();
            while (rs.next()) {
                String date = rs.getString("date");
                Record record = new Record(
                        rs.getString("date"),
                        rs.getString("time"),
                        rs.getDouble("sugar"),
                        rs.getDouble("carbs"),
                        rs.getString("insulin")
                );
                recordsByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(record);
            }

            if (recordsByDate.isEmpty()) {
                sendMessage(chatId, "Нет данных для отчета.", new ReplyKeyboardRemove());
                return;
            }

            for (String date : recordsByDate.keySet()) {
                Workbook workbook = new XSSFWorkbook();
                Sheet sheet = workbook.createSheet("Report_" + date.replace(".", "_"));

                // Заголовки
                Row headerRow = sheet.createRow(0);
                String[] headers = {"Час", "Сахар", "Хлебные единицы", "Инсулин"};
                for (int i = 0; i < headers.length; i++) {
                    headerRow.createCell(i).setCellValue(headers[i]);
                }

                // Данные по часам
                List<Record> records = recordsByDate.get(date);
                for (int hour = 0; hour < 24; hour++) {
                    Row row = sheet.createRow(hour + 1);
                    row.createCell(0).setCellValue(String.format("%02d:00", hour));
                    row.createCell(1).setCellValue("-");
                    row.createCell(2).setCellValue("-");
                    row.createCell(3).setCellValue("-");

                    for (Record record : records) {
                        int recordHour = Integer.parseInt(record.time.split(":")[0]);
                        if (recordHour == hour) {
                            row.createCell(1).setCellValue(record.sugar);
                            row.createCell(2).setCellValue(record.carbs);
                            row.createCell(3).setCellValue(record.insulin);
                        }
                    }
                }

                // Сохранение файла
                String filename = "report_" + date.replace(".", "_") + ".xlsx";
                try (java.io.FileOutputStream fileOut = new java.io.FileOutputStream(filename)) {
                    workbook.write(fileOut);
                }
                workbook.close();

                // Отправка файла
                SendDocument document = new SendDocument();
                document.setChatId(String.valueOf(chatId));
                document.setDocument(new InputFile(new File(filename)));
                execute(document);

                // Удаление файла
                new File(filename).delete();
            }
        } catch (SQLException | java.io.IOException e) {
            e.printStackTrace();
            sendMessage(chatId, "Ошибка при генерации отчета.", new ReplyKeyboardRemove());
        }
    }

    private void sendMessage(long chatId, String text, ReplyKeyboardRemove replyKeyboardRemove) throws TelegramApiException {
        sendMessage(chatId, text, (ReplyKeyboardRemove) null);
    }

    private void sendMessage(long chatId, String text, ReplyKeyboardMarkup keyboard) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        if (keyboard != null) {
            message.setReplyMarkup(keyboard);
        }
        execute(message);
    }

    private ReplyKeyboardMarkup createKeyboard(List<String> buttons) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setOneTimeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        for (String button : buttons) {
            row.add(button);
        }
        keyboard.add(row);
        keyboardMarkup.setKeyboard(keyboard);
        return keyboardMarkup;
    }

    private static class Record {
        String date, time, insulin;
        double sugar, carbs;

        Record(String date, String time, double sugar, double carbs, String insulin) {
            this.date = date;
            this.time = time;
            this.sugar = sugar;
            this.carbs = carbs;
            this.insulin = insulin;
        }
    }
}