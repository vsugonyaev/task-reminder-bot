# task-reminder-bot (Java + Telegram + Quartz)

Напоминает команде о закрытии задач по расписанию: **Пн/Ср/Пт в 16:30 (MSK, Europe/Moscow)**.

## Требования
- Java 17+
- Maven 3.8+

## Настройка
Создайте бота в @BotFather и получите токен.

Укажите переменные окружения:
- `TG_BOT_TOKEN` — токен бота
- `TG_BOT_USERNAME` — username бота (без @)
- `TG_CHAT_ID` — id чата/группы/канала, куда слать сообщения

### Как получить chatId группы
1. Добавьте бота в группу
2. Напишите в группе команду: `/chatid`
3. Бот ответит `chatId: ...` — это значение и нужно

## Запуск в IntelliJ IDEA
1. File → Open → выберите папку проекта
2. Дождитесь импорта Maven
3. Run → Edit Configurations… → добавьте Application:
   - Main class: `com.example.Main`
   - Environment variables: задайте TG_BOT_TOKEN, TG_BOT_USERNAME, TG_CHAT_ID
4. Run

## Сборка fat-jar
```bash
mvn -DskipTests package
java -jar target/task-reminder-bot-1.0.0.jar
```
