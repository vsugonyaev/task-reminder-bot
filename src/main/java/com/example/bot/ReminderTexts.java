package com.example.bot;

import com.example.sprint.SprintSchedule.Sprint;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Reminder texts (Telegram HTML).
 *
 * Regular texts are shuffled once per sprint and picked by the day of the sprint,
 * so they never repeat within a sprint and come in a different order every sprint.
 */
public final class ReminderTexts {

    private static final String FOOTER = "\n\n🛑 Есть блокеры — пишите в чат.";

    private static final List<String> REGULAR = List.of(
            "<b>⏰ ЗАКРЫВАЕМ ЗАДАЧИ!</b>\n"
                    + "Задача в статусе «почти готово» — это как «почти сдал сессию». Не считается 🎓",

            "<b>📌 ВРЕМЯ ДВИГАТЬ КАРТОЧКИ!</b>\n"
                    + "Доска сама себя не разгребёт. Мы проверяли — три спринта ждали 🕸",

            "<b>🔥 ПОРА ЗАКРЫВАТЬ ЗАДАЧИ!</b>\n"
                    + "Незакрытая задача ночью приходит к своему исполнителю и тихо спрашивает: «За что?» 👻",

            "<b>✅ ЧЕК-ИН ПО ЗАДАЧАМ!</b>\n"
                    + "Сделал, но не закрыл — как приготовил ужин и не позвал никого есть 🍝",

            "<b>🧹 ГЕНЕРАЛЬНАЯ УБОРКА ДОСКИ!</b>\n"
                    + "Мари Кондо спрашивает: эта задача в In Progress приносит вам радость? 🙂",

            "<b>⏳ ТИК-ТАК, ЗАДАЧИ!</b>\n"
                    + "Скрам-мастер не плачет. Просто доска грустная, а он рядом стоял 🥲",

            "<b>🚀 ЗАКРЫВАЕМ — И НА ВЗЛЁТ!</b>\n"
                    + "Хьюстон, у нас проблема: задачи готовы, а статусы — нет 🛰",

            "<b>📊 БЕРНДАУН ЖДЁТ!</b>\n"
                    + "Помогите графику сгорания сгореть. Сам он только тлеет 🔥",

            "<b>🦥 ЛЕНИВЕЦ ДЕТЕКТЕД!</b>\n"
                    + "Где-то есть задача, которая сделана ещё во вторник. Она всё ещё надеется ✨",

            "<b>🎯 ЦЕЛЬ ДНЯ: ЗАКРЫТЬ ЗАДАЧИ!</b>\n"
                    + "Ставка простая: закрываешь задачу — карма +1, тимлид улыбается 😌",

            "<b>🧩 СОБИРАЕМ ПАЗЛ СПРИНТА!</b>\n"
                    + "Каждая закрытая задача — деталька. Пока что у нас картина «Чёрный квадрат» ⬛",

            "<b>📣 ВНИМАНИЕ, ЭТО НЕ УЧЕБНАЯ ТРЕВОГА!</b>\n"
                    + "Проверьте задачи. Сработало? Отлично, тогда закройте их 🚨",

            "<b>☕ КОФЕ-БРЕЙК ОТМЕНЯЕТСЯ!</b>\n"
                    + "Шутка. Но сначала закройте задачи — потом кофе вкуснее ☕",

            "<b>🐢 МЕДЛЕННО, НО ВЕРНО!</b>\n"
                    + "Даже черепаха двигает свою карточку в Done. А ты чем хуже? 🏁",

            "<b>🔔 ДИНЬ-ДОН, ЗАДАЧИ!</b>\n"
                    + "Статус «В работе» с прошлой недели — это уже не работа, это образ жизни 🧘",

            "<b>🕵️ РАССЛЕДОВАНИЕ ДНЯ!</b>\n"
                    + "Кто оставил задачу открытой? Улики указывают на всех. Алиби — только у закрывших 🔍",

            "<b>🏋️ ЕЖЕДНЕВНАЯ ТРЕНИРОВКА!</b>\n"
                    + "Подход 1: открыть доску. Подход 2: закрыть задачи. Растяжка — по желанию 💪",

            "<b>🌚 ДОСКА ВИДИТ ВСЁ!</b>\n"
                    + "Она помнит каждую задачу, которую «вот-вот закроют». Не разочаровывайте её 👁",

            "<b>📦 ОТГРУЗКА ЗАДАЧ!</b>\n"
                    + "Готовые задачи сами в Done не доставляются. Курьер — это вы 🚚",

            "<b>🎮 ЕЖЕДНЕВНЫЙ КВЕСТ!</b>\n"
                    + "Задание: закрыть задачи. Награда: +100 к репутации и чистая совесть 🏆",

            "<b>🧠 ФАКТ ДНЯ!</b>\n"
                    + "Учёные доказали: закрытая задача весит меньше открытой. Облегчите себе жизнь 🔬",

            "<b>🦉 СОВЫ И ЖАВОРОНКИ, ВНИМАНИЕ!</b>\n"
                    + "Неважно, когда вы работаете, — важно, когда вы закрываете задачи. Например, сейчас ⏰",

            "<b>🍕 ПЯТЬ МИНУТ НА ДОСКУ!</b>\n"
                    + "Меньше, чем ждать доставку пиццы. И даже чаевые давать не надо 🛵",

            "<b>🪄 МАГИЯ АДЖАЙЛА!</b>\n"
                    + "Говоришь «Done» — и задача исчезает с доски. Работает, только если правда сделал 🎩"
    );

    private static final List<String> LAST_DAY = List.of(
            "<b>🚨 ПОСЛЕДНИЙ ДЕНЬ СПРИНТА! 🚨</b>\n"
                    + "Это не репетиция. Всё, что сделано, — в Done. Всё, что не сделано, — честно в чат 🙏",

            "<b>🏁 ФИНИШНАЯ ПРЯМАЯ!</b>\n"
                    + "Сегодня последний день спринта. Завтра эти задачи станут «долгом прошлого спринта», а это звучит грустно 😢",

            "<b>⚠️ СПРИНТ ЗАКАНЧИВАЕТСЯ СЕГОДНЯ!</b>\n"
                    + "Ретро завтра. Давайте обсуждать успехи, а не «почему опять не успели» 🎬",

            "<b>🎆 ГРАНД-ФИНАЛ СПРИНТА!</b>\n"
                    + "Последний шанс закрыть задачи с гордостью, а не с объяснительной 📝"
    );

    private static final String SCRUM_MASTER = "@vsugonyaev";

    /** Sprint planning day warnings: red "stop" banner + quote, so they look nothing like regular reminders. */
    private static final String SPRINT_START_HEADER =
            "⛔⛔⛔⛔⛔⛔⛔⛔\n"
                    + "<b>🚫 СЕГОДНЯ ЗАДАЧИ НЕ ЗАКРЫВАЕМ! 🚫</b>\n"
                    + "⛔⛔⛔⛔⛔⛔⛔⛔\n\n"
                    + "Первый день спринта — планирование. Ни одна задача сегодня не должна уйти в Done.\n\n";

    private static final String SPRINT_START_FOOTER =
            "\n\n🆘 <b>Случайно закрыли?</b> Сразу пишите скраму " + SCRUM_MASTER + " — исправим, пока не поздно.";

    private static final List<String> SPRINT_START = List.of(
            "<blockquote>Закрыть задачу в первый день спринта — как съесть торт до того, как задуть свечи. "
                    + "Вкусно, но все расстроятся 🎂</blockquote>",

            "<blockquote>Сегодня кнопка «Done» — это лава. Не наступаем 🌋</blockquote>",

            "<blockquote>Закрыть задачу сегодня — как прийти на финиш марафона на старте. "
                    + "Быстро, но медаль не дадут 🏅</blockquote>",

            "<blockquote>Руки прочь от статусов! Сегодня мы только планируем, мечтаем и оцениваем 🧘</blockquote>",

            "<blockquote>Джира сегодня в режиме «только смотреть». Как музей: трогать экспонаты нельзя 🖼</blockquote>",

            "<blockquote>Если очень хочется что-то закрыть — закройте вкладку с мемами. Задачи — завтра 😉</blockquote>"
    );

    private ReminderTexts() {}

    public static String sprintStart(Sprint sprint) {
        return SPRINT_START_HEADER
                + SPRINT_START.get(Math.floorMod(sprint.number(), SPRINT_START.size()))
                + SPRINT_START_FOOTER;
    }

    public static String regular(Sprint sprint, LocalDate date) {
        List<String> shuffled = new ArrayList<>(REGULAR);
        Collections.shuffle(shuffled, new Random(sprint.start().toEpochDay()));
        int dayOfSprint = (int) ChronoUnit.DAYS.between(sprint.start(), date);
        return shuffled.get(dayOfSprint % shuffled.size()) + FOOTER;
    }

    public static String lastDay(Sprint sprint) {
        return LAST_DAY.get(Math.floorMod(sprint.number(), LAST_DAY.size())) + FOOTER;
    }

    public static String forDay(Sprint sprint, LocalDate date, boolean lastDay) {
        return lastDay ? lastDay(sprint) : regular(sprint, date);
    }
}
