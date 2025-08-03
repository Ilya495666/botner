package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.sql.*;
import java.util.*;

public class BotOk extends TelegramLongPollingBot {
    // Получаем параметры из переменных окружения Railway
    private static final String DB_URL = System.getenv("DB_URL");
    private static final String DB_USER = System.getenv("DB_USER");
    private static final String DB_PASSWORD = System.getenv("DB_PASSWORD");

    // Список chatId педагогов
    private static final Set<Long> TEACHER_IDS = Set.of(
            7241469346L, // Пример: твой ID
            1204112225L, // Пример: Наталья
            5228430828L  // Пример: Кристина
    );

    private enum BookingState {
        SELECT_DAY,
        SELECT_TIME,
        ENTER_CHILD_NAME,
        CONFIRM,
        CANCEL_SELECT,
        TEACHER_MENU
    }

    private static class UserState {
        BookingState state;
        String selectedDay;
        String selectedTime;
        int scheduleId;
        String childName;
        Map<Integer, Integer> bookingOptions;
    }

    private final Map<Long, UserState> userStates = new HashMap<>();

    @Override
    public String getBotUsername() {
        return "clubOstrovOk_bot";
    }

    @Override
    public String getBotToken() {
        return System.getenv("BOT_TOKEN");
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String messageText = update.getMessage().getText();

            // Для педагогов отдельная логика
            if (TEACHER_IDS.contains(chatId)) {
                if (!userStates.containsKey(chatId) || messageText.equals("/start") || messageText.equals("↩️ На главную")) {
                    UserState userState = new UserState();
                    userState.state = BookingState.TEACHER_MENU;
                    userStates.put(chatId, userState);
                    sendTeacherMenu(chatId);
                    return;
                }
                if (userStates.get(chatId).state == BookingState.TEACHER_MENU) {
                    handleTeacherCommand(chatId, messageText);
                    return;
                }
            }

            // Обычные пользователи
            if (messageText.equals("/start")) {
                sendStartMessage(chatId);
            } else if (messageText.equals("📅 Расписание")) {
                sendSchedule(chatId);
            } else if (messageText.equals("📝 Записаться") || messageText.equals("↩️ Назад")) {
                startBookingProcess(chatId);
            } else if (messageText.equals("❌ Отменить запись")) {
                startCancelBookingProcess(chatId);
            } else if (userStates.containsKey(chatId)) {
                try {
                    processBookingStep(chatId, messageText);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            } else {
                sendMessage(chatId, "Используйте кнопки меню");
            }
        }
    }

    // ========== МЕНЮ ПЕДАГОГА ==========
    private void handleTeacherCommand(long chatId, String messageText) {
        if (messageText.equals("👥 Мои записи")) {
            showTeacherBookings(chatId);
        } else if (messageText.equals("📊 Отчет за сегодня")) {
            sendDailyReport(chatId);
        } else if (messageText.equals("↩️ На главную")) {
            sendTeacherMenu(chatId);
        } else {
            sendMessage(chatId, "Неизвестная команда. Используйте меню.");
        }
    }

    private void sendTeacherMenu(long chatId) {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("👥 Мои записи"));
        row1.add(new KeyboardButton("📊 Отчет за сегодня"));
        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("↩️ На главную"));
        rows.add(row1);
        rows.add(row2);
        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("👩‍🏫 Личный кабинет педагога\nВыберите действие:");
        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void showTeacherBookings(long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT s.day, s.time, b.child_name " +
                             "FROM schedule s LEFT JOIN bookings b ON s.id = b.schedule_id " +
                             "ORDER BY s.day, s.time, b.child_name")) {

            ResultSet rs = pstmt.executeQuery();
            StringBuilder sb = new StringBuilder("👥 Записи на занятия 'Нейрогимнастика':\n\n");
            String currentDay = "";
            String currentTime = "";
            while (rs.next()) {
                String day = rs.getString("day");
                String time = rs.getString("time");
                String child = rs.getString("child_name");

                if (!day.equals(currentDay) || !time.equals(currentTime)) {
                    sb.append("\n📅 ").append(day).append(" ").append(time).append("\n");
                    currentDay = day;
                    currentTime = time;
                }
                if (child != null) {
                    sb.append("   👶 ").append(child).append("\n");
                }
            }
            sendMessage(chatId, sb.toString());
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при получении записей.");
        }
    }

    private void sendDailyReport(long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT s.day, s.time, b.child_name " +
                             "FROM bookings b JOIN schedule s ON b.schedule_id = s.id " +
                             "WHERE DATE(b.booking_date) = CURRENT_DATE " +
                             "ORDER BY s.day, s.time")) {

            ResultSet rs = pstmt.executeQuery();
            StringBuilder sb = new StringBuilder("📊 Отчет за сегодня:\n\n");
            String currentDay = "";
            String currentTime = "";
            while (rs.next()) {
                String day = rs.getString("day");
                String time = rs.getString("time");
                String child = rs.getString("child_name");

                if (!day.equals(currentDay) || !time.equals(currentTime)) {
                    sb.append("\n📅 ").append(day).append(" ").append(time).append("\n");
                    currentDay = day;
                    currentTime = time;
                }
                sb.append("   👶 ").append(child).append("\n");
            }
            sendMessage(chatId, sb.toString());
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при формировании отчета.");
        }
    }

    // ========== КЛИЕНТСКОЕ МЕНЮ ==========
    private void startBookingProcess(long chatId) {
        UserState userState = new UserState();
        userState.state = BookingState.SELECT_DAY;
        userStates.put(chatId, userState);
        sendDaySelection(chatId);
    }

    private void processBookingStep(long chatId, String messageText) throws SQLException {
        UserState userState = userStates.get(chatId);
        switch (userState.state) {
            case SELECT_DAY -> handleDaySelection(chatId, messageText);
            case SELECT_TIME -> handleTimeSelection(chatId, messageText);
            case ENTER_CHILD_NAME -> handleChildNameInput(chatId, messageText);
            case CONFIRM -> handleConfirmation(chatId, messageText);
            case CANCEL_SELECT -> handleCancelBooking(chatId, messageText);
            default -> sendMessage(chatId, "Неизвестная команда.");
        }
    }

    private void sendDaySelection(long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT day FROM schedule")) {
            List<String> days = new ArrayList<>();
            while (rs.next()) days.add(rs.getString("day"));
            sendKeyboard(chatId, "Выберите день для записи на 'Нейрогимнастику':", days);
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при загрузке дней");
        }
    }

    private void handleDaySelection(long chatId, String day) {
        UserState userState = userStates.get(chatId);
        userState.selectedDay = day;
        userState.state = BookingState.SELECT_TIME;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT id, time FROM schedule WHERE day = ? AND booked_places < max_places")) {
            pstmt.setString(1, day);
            ResultSet rs = pstmt.executeQuery();
            List<String> times = new ArrayList<>();
            while (rs.next()) times.add(rs.getString("time"));
            if (times.isEmpty()) {
                sendMessage(chatId, "❌ На выбранный день нет свободных мест");
                sendDaySelection(chatId);
            } else {
                sendKeyboard(chatId, "Выберите время:", times);
            }
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при загрузке времени");
        }
    }

    private void handleTimeSelection(long chatId, String time) {
        UserState userState = userStates.get(chatId);
        userState.selectedTime = time;
        userState.state = BookingState.ENTER_CHILD_NAME;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT id FROM schedule WHERE day = ? AND time = ?")) {
            pstmt.setString(1, userState.selectedDay);
            pstmt.setString(2, time);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                userState.scheduleId = rs.getInt("id");
                sendMessage(chatId, "Введите имя и фамилию ребёнка:");
            }
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при подтверждении времени");
        }
    }

    private void handleChildNameInput(long chatId, String childName) {
        UserState userState = userStates.get(chatId);
        userState.childName = childName;
        userState.state = BookingState.CONFIRM;
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton("✅ Подтвердить"));
        row.add(new KeyboardButton("↩️ Назад"));
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Подтвердите запись:\n\n" +
                "👶 Ребёнок: " + childName + "\n" +
                "📚 Курс: Нейрогимнастика\n" +
                "📅 День: " + userState.selectedDay + "\n" +
                "⏰ Время: " + userState.selectedTime + "\n\n" +
                "Всё верно?");
        message.setReplyMarkup(keyboard);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleConfirmation(long chatId, String confirmation) {
        UserState userState = userStates.get(chatId);
        if (confirmation.equals("✅ Подтвердить")) {
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt1 = conn.prepareStatement(
                        "UPDATE schedule SET booked_places = booked_places + 1 WHERE id = ? AND booked_places < max_places")) {
                    pstmt1.setInt(1, userState.scheduleId);
                    int updated = pstmt1.executeUpdate();
                    if (updated == 0) {
                        sendMessage(chatId, "❌ Места закончились. Пожалуйста, выберите другое время.");
                        sendDaySelection(chatId);
                        return;
                    }
                }
                try (PreparedStatement pstmt2 = conn.prepareStatement(
                        "INSERT INTO bookings (user_id, child_name, schedule_id) VALUES (?, ?, ?)")) {
                    pstmt2.setLong(1, chatId);
                    pstmt2.setString(2, userState.childName);
                    pstmt2.setInt(3, userState.scheduleId);
                    pstmt2.executeUpdate();
                }
                conn.commit();
                sendMessage(chatId, "✅ Запись успешно оформлена на имя: " + userState.childName + "\n\n" +
                        "Курс: Нейрогимнастика\n" +
                        "День: " + userState.selectedDay + "\n" +
                        "Время: " + userState.selectedTime);
                sendStartMessage(chatId);
            } catch (Exception e) {
                sendMessage(chatId, "Ошибка при оформлении записи");
            }
        } else {
            sendDaySelection(chatId);
        }
        userStates.remove(chatId);
    }

    private void startCancelBookingProcess(long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT b.id, s.day, s.time, b.child_name " +
                             "FROM bookings b JOIN schedule s ON b.schedule_id = s.id " +
                             "WHERE b.user_id = ? ORDER BY b.id")) {
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            List<String> bookings = new ArrayList<>();
            Map<Integer, Integer> bookingIds = new HashMap<>();
            int count = 1;
            while (rs.next()) {
                String bookingInfo = String.format("%d. %s - %s %s",
                        count, rs.getString("child_name"), rs.getString("day"), rs.getString("time"));
                bookings.add(bookingInfo);
                bookingIds.put(count, rs.getInt("id"));
                count++;
            }
            if (bookings.isEmpty()) {
                sendMessage(chatId, "У вас нет активных записей.");
                return;
            }
            UserState userState = new UserState();
            userState.state = BookingState.CANCEL_SELECT;
            userState.bookingOptions = bookingIds;
            userStates.put(chatId, userState);
            StringBuilder messageText = new StringBuilder("Ваши записи:\n\n");
            for (String booking : bookings) messageText.append(booking).append("\n");
            messageText.append("\nВведите номер записи для отмены:");
            sendMessage(chatId, messageText.toString());
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при получении списка записей");
        }
    }

    private void handleCancelBooking(long chatId, String input) {
        UserState userState = userStates.get(chatId);
        try {
            int choice = Integer.parseInt(input);
            Integer bookingId = userState.bookingOptions.get(choice);
            if (bookingId == null) {
                sendMessage(chatId, "Неверный номер записи. Попробуйте еще раз.");
                return;
            }
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                conn.setAutoCommit(false);
                int scheduleId;
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT schedule_id FROM bookings WHERE id = ?")) {
                    pstmt.setInt(1, bookingId);
                    ResultSet rs = pstmt.executeQuery();
                    if (!rs.next()) {
                        sendMessage(chatId, "Запись не найдена.");
                        return;
                    }
                    scheduleId = rs.getInt("schedule_id");
                }
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "DELETE FROM bookings WHERE id = ?")) {
                    pstmt.setInt(1, bookingId);
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "UPDATE schedule SET booked_places = booked_places - 1 WHERE id = ?")) {
                    pstmt.setInt(1, scheduleId);
                    pstmt.executeUpdate();
                }
                conn.commit();
                sendMessage(chatId, "✅ Запись успешно отменена.");
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Пожалуйста, введите номер записи.");
        } catch (Exception e) {
            sendMessage(chatId, "Ошибка при отмене записи");
        } finally {
            userStates.remove(chatId);
            sendStartMessage(chatId);
        }
    }

    private void sendKeyboard(long chatId, String text, List<String> options) {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < options.size(); i += 2) {
            KeyboardRow row = new KeyboardRow();
            row.add(new KeyboardButton(options.get(i)));
            if (i + 1 