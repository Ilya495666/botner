package org.example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import java.sql.*;

public class Main {
    public static void main(String[] args) {
        initializeDatabase();
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new BotOk());
            System.out.println("Бот успешно запущен!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private static void initializeDatabase() {
        String url = System.getenv("DATABASE_URL"); // Для Railway
        // Или для SQLite:
        // String url = "jdbc:sqlite:kids_club.db";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // Удаляем таблицы, если они существуют
            stmt.execute("DROP TABLE IF EXISTS bookings");
            stmt.execute("DROP TABLE IF EXISTS schedule");

            // Создаём заново
            stmt.execute("""
            CREATE TABLE schedule (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                day TEXT NOT NULL,
                time TEXT NOT NULL,
                max_places INTEGER NOT NULL,
                booked_places INTEGER DEFAULT 0
            )""");

            stmt.execute("""
            CREATE TABLE bookings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                child_name TEXT NOT NULL,
                schedule_id INTEGER NOT NULL,
                booking_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )""");

            // Вставляем актуальное расписание
            insertInitialData(conn);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void insertInitialData(Connection conn) throws SQLException {
        Object[][] scheduleData = {
                // Убрали "Вторник", добавили "Четверг" и новое время в "Пятницу"
                {"Среда", "12:00-12:40", 6},
                {"Среда", "17:30-18:10", 6},
                {"Среда", "18:20-19:00", 6},
                {"Среда", "19:10-19:50", 6},
                {"Четверг", "18:20-19:00", 6},  // Новое время в четверг
                {"Пятница", "12:00-12:40", 6},
                {"Пятница", "17:30-18:10", 6},  // Новое время в пятницу
                {"Суббота", "10:30-11:10", 6},
                {"Суббота", "11:20-12:00", 6},
                {"Суббота", "13:00-13:40", 6}
        };

        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO schedule (day, time, max_places) VALUES (?, ?, ?)")) {
            // Очищаем старые данные
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM schedule");
            }

            // Добавляем новые
            for (Object[] data : scheduleData) {
                pstmt.setString(1, (String) data[0]);
                pstmt.setString(2, (String) data[1]);
                pstmt.setInt(3, (Integer) data[2]);
                pstmt.executeUpdate();
            }
        }
    }
    }
