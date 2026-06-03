#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <FFat.h>
#include <FS.h>
#include "ST7305_U8g2.h"

#define LCD_WIDTH 400
#define LCD_HEIGHT 300
#define IMAGE_BYTES ((LCD_WIDTH / 8) * LCD_HEIGHT)
#define MAX_TEXT_BYTES 4096
#define TEXT_MARGIN_X 18
#define TEXT_MARGIN_Y 18
#define TEXT_MAX_WIDTH (LCD_WIDTH - (TEXT_MARGIN_X * 2))
#define TEXT_MAX_LINES 8
#define TEXT_BASE_LINE_HEIGHT 26
#define TEXT_MAX_SCALE 3
#define TEXT_MIN_SCALE 2
#define TEXT_SOURCE_X 4
#define TEXT_SOURCE_Y 12
#define TEXT_SOURCE_TOP_PADDING 8
#define TEXT_SOURCE_BOTTOM_PADDING 8
#define DASHBOARD_DEFAULT_TEMP_C 8
#define DASHBOARD_DEFAULT_HUMIDITY 45
#define DASHBOARD_DEFAULT_BATTERY 76

#define RLCD_SCK_PIN 11
#define RLCD_MOSI_PIN 12
#define RLCD_DC_PIN 5
#define RLCD_CS_PIN 40
#define RLCD_RST_PIN 41

#define BUTTON_BOOT_PIN 0
#define BUTTON_KEY_PIN 18
#define BUTTON_LONG_PRESS_MS 1200
#define BUTTON_DEBOUNCE_MS 30

static const char *BLE_DEVICE_NAME = "RLCD-BLE-IMG";
static const char *SERVICE_UUID = "7f6b0001-5f02-4fd8-9f23-6f8e4c59a001";
static const char *CONTROL_UUID = "7f6b0002-5f02-4fd8-9f23-6f8e4c59a001";
static const char *DATA_UUID = "7f6b0003-5f02-4fd8-9f23-6f8e4c59a001";
static const char *STATUS_UUID = "7f6b0004-5f02-4fd8-9f23-6f8e4c59a001";
static const char *LAST_IMAGE_PATH = "/last_image.bin";
static const char *LAST_TEXT_PATH = "/last_text.txt";
static const char *LAST_PAGE_PATH = "/last_page.txt";

enum ReceiveMode : uint8_t {
  RECEIVE_NONE = 0,
  RECEIVE_IMAGE = 1,
  RECEIVE_TEXT = 2
};

enum DisplayPage : uint8_t {
  PAGE_DEFAULT = 0,
  PAGE_IMAGE = 1,
  PAGE_TEXT = 2
};

struct ButtonTracker {
  uint8_t pin;
  DisplayPage shortPage;
  bool wasDown;
  bool longHandled;
  uint32_t downAt;
};

struct DashboardDateTime {
  uint16_t year;
  uint8_t month;
  uint8_t day;
  uint8_t hour;
  uint8_t minute;
};

static ST7305_U8g2 lcd(RLCD_SCK_PIN, RLCD_MOSI_PIN, RLCD_DC_PIN, RLCD_CS_PIN, RLCD_RST_PIN);
static U8G2 *u8g2 = nullptr;

static BLECharacteristic *statusCharacteristic = nullptr;
static uint8_t imageBuffer[IMAGE_BYTES];
static char textBuffer[MAX_TEXT_BYTES + 1];
static volatile bool imageReady = false;
static volatile bool textReady = false;
static volatile bool deviceConnected = false;
static volatile uint32_t expectedBytes = 0;
static volatile uint32_t receivedBytes = 0;
static volatile uint16_t expectedSeq = 0;
static volatile ReceiveMode receiveMode = RECEIVE_NONE;
static volatile bool saveImagePending = false;
static volatile bool saveTextPending = false;
static bool storageReady = false;
static bool hasSavedImage = false;
static bool hasSavedText = false;
static DisplayPage currentPage = PAGE_DEFAULT;
static uint32_t dashboardClockBaseSeconds = 0;
static uint32_t dashboardClockBaseMillis = 0;
static int lastDashboardMinuteKey = -1;

static ButtonTracker keyButton = {BUTTON_KEY_PIN, PAGE_IMAGE, false, false, 0};
static ButtonTracker bootButton = {BUTTON_BOOT_PIN, PAGE_TEXT, false, false, 0};

static void showPage(DisplayPage page, bool persist);

static void drawCenteredStr(int y, const char *text)
{
  int textWidth = u8g2->getStrWidth(text);
  int x = (LCD_WIDTH - textWidth) / 2;
  if (x < 0) {
    x = 0;
  }
  u8g2->drawStr(x, y, text);
}

static void drawCenteredUTF8(int y, const char *text)
{
  int textWidth = u8g2->getUTF8Width(text);
  int x = (LCD_WIDTH - textWidth) / 2;
  if (x < 0) {
    x = 0;
  }
  u8g2->drawUTF8(x, y, text);
}

static void drawCenteredStrAt(int y, const char *text)
{
  int textWidth = u8g2->getStrWidth(text);
  int x = (LCD_WIDTH - textWidth) / 2;
  if (x < 0) {
    x = 0;
  }
  u8g2->drawStr(x, y, text);
}

static bool dashboardLeapYear(uint16_t year)
{
  return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
}

static uint8_t dashboardMonthDays(uint16_t year, uint8_t month)
{
  static const uint8_t days[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  if (month == 2 && dashboardLeapYear(year)) {
    return 29;
  }
  return days[month - 1];
}

static uint8_t dashboardBuildMonth(const char *month)
{
  static const char *months[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
  for (uint8_t i = 0; i < 12; i++) {
    if (strncmp(month, months[i], 3) == 0) {
      return i + 1;
    }
  }
  return 1;
}

static uint32_t dashboardSecondsFromDate(uint16_t year, uint8_t month, uint8_t day, uint8_t hour, uint8_t minute, uint8_t second)
{
  uint32_t days = 0;
  for (uint16_t y = 2000; y < year; y++) {
    days += dashboardLeapYear(y) ? 366 : 365;
  }
  for (uint8_t m = 1; m < month; m++) {
    days += dashboardMonthDays(year, m);
  }
  days += day - 1;
  return days * 86400UL + (uint32_t)hour * 3600UL + (uint32_t)minute * 60UL + second;
}

static void initDashboardClock()
{
  const char *buildDate = __DATE__;
  const char *buildTime = __TIME__;
  uint16_t year = (uint16_t)atoi(buildDate + 7);
  uint8_t month = dashboardBuildMonth(buildDate);
  uint8_t day = (uint8_t)atoi(buildDate + 4);
  uint8_t hour = (uint8_t)((buildTime[0] - '0') * 10 + (buildTime[1] - '0'));
  uint8_t minute = (uint8_t)((buildTime[3] - '0') * 10 + (buildTime[4] - '0'));
  uint8_t second = (uint8_t)((buildTime[6] - '0') * 10 + (buildTime[7] - '0'));

  dashboardClockBaseSeconds = dashboardSecondsFromDate(year, month, day, hour, minute, second);
  dashboardClockBaseMillis = millis();
}

static void setDashboardClock(uint16_t year, uint8_t month, uint8_t day, uint8_t hour, uint8_t minute, uint8_t second)
{
  if (month < 1 || month > 12 || day < 1 || day > dashboardMonthDays(year, month) || hour > 23 || minute > 59 || second > 59) {
    return;
  }

  dashboardClockBaseSeconds = dashboardSecondsFromDate(year, month, day, hour, minute, second);
  dashboardClockBaseMillis = millis();
  lastDashboardMinuteKey = -1;

  if (currentPage == PAGE_DEFAULT) {
    drawDefaultPage();
  }
}

static DashboardDateTime dashboardNow()
{
  uint32_t elapsed = (millis() - dashboardClockBaseMillis) / 1000UL;
  uint32_t seconds = dashboardClockBaseSeconds + elapsed;
  uint32_t days = seconds / 86400UL;
  uint32_t daySeconds = seconds % 86400UL;

  DashboardDateTime now;
  now.hour = daySeconds / 3600UL;
  now.minute = (daySeconds % 3600UL) / 60UL;

  now.year = 2000;
  while (true) {
    uint16_t yearDays = dashboardLeapYear(now.year) ? 366 : 365;
    if (days < yearDays) {
      break;
    }
    days -= yearDays;
    now.year++;
  }

  now.month = 1;
  while (true) {
    uint8_t monthDays = dashboardMonthDays(now.year, now.month);
    if (days < monthDays) {
      break;
    }
    days -= monthDays;
    now.month++;
  }

  now.day = days + 1;
  return now;
}

static int dashboardMinuteKey(const DashboardDateTime &now)
{
  return (((int)now.month * 31 + now.day) * 24 + now.hour) * 60 + now.minute;
}

static void notifyStatus(const char *status)
{
  Serial.println(status);
  if (statusCharacteristic != nullptr) {
    statusCharacteristic->setValue(status);
    statusCharacteristic->notify();
  }
}

static void drawWaitingScreen(const char *status)
{
  u8g2->clearBuffer();
  u8g2->setDrawColor(1);
  u8g2->drawFrame(10, 10, 380, 280);
  u8g2->drawFrame(18, 18, 364, 264);

  u8g2->drawCircle(200, 118, 54);
  u8g2->drawCircle(200, 118, 34);
  u8g2->drawLine(200, 64, 200, 172);
  u8g2->drawLine(146, 118, 254, 118);
  u8g2->drawLine(162, 80, 238, 156);
  u8g2->drawLine(238, 80, 162, 156);

  u8g2->setFont(u8g2_font_10x20_tf);
  drawCenteredStr(214, "RLCD BLE");
  u8g2->setFont(u8g2_font_6x13_tf);
  drawCenteredStr(246, status);
  drawCenteredStr(266, BLE_DEVICE_NAME);
  u8g2->sendBuffer();
}

static void drawBatteryIcon(int x, int y, int width, int height, int percent)
{
  if (percent < 0) {
    percent = 0;
  }
  if (percent > 100) {
    percent = 100;
  }

  u8g2->drawRFrame(x, y, width, height, 3);
  u8g2->drawBox(x + width, y + height / 4, 4, height / 2);

  int innerWidth = width - 8;
  int fillWidth = innerWidth * percent / 100;
  if (fillWidth > 0) {
    u8g2->drawBox(x + 4, y + 4, fillWidth, height - 8);
  }
}

static void drawDefaultPage()
{
  DashboardDateTime now = dashboardNow();
  char tempText[16];
  char humidityText[16];
  char dateText[16];
  char timeText[8];

  snprintf(tempText, sizeof(tempText), "%dC", DASHBOARD_DEFAULT_TEMP_C);
  snprintf(humidityText, sizeof(humidityText), "%d%%", DASHBOARD_DEFAULT_HUMIDITY);
  snprintf(dateText, sizeof(dateText), "%04u.%02u.%02u", now.year, now.month, now.day);
  snprintf(timeText, sizeof(timeText), "%02u:%02u", now.hour, now.minute);

  u8g2->clearBuffer();
  u8g2->setDrawColor(1);

  u8g2->drawFrame(8, 8, 384, 284);
  u8g2->drawHLine(24, 62, 352);

  u8g2->setFont(u8g2_font_9x18B_tf);
  u8g2->drawStr(24, 42, dateText);
  u8g2->setFont(u8g2_font_fub20_tn);
  u8g2->drawStr(194, 44, tempText);
  u8g2->setFont(u8g2_font_10x20_tf);
  u8g2->drawStr(260, 43, humidityText);

  drawBatteryIcon(337, 24, 42, 22, DASHBOARD_DEFAULT_BATTERY);

  u8g2->setFont(u8g2_font_logisoso92_tn);
  int timeWidth = u8g2->getStrWidth(timeText);
  int timeX = (LCD_WIDTH - timeWidth) / 2;
  if (timeX < 8) {
    timeX = 8;
  }
  u8g2->drawStr(timeX, 217, timeText);

  u8g2->setFont(u8g2_font_6x13_tf);
  drawCenteredStrAt(276, "power by zstar");
  u8g2->sendBuffer();
  currentPage = PAGE_DEFAULT;
  lastDashboardMinuteKey = dashboardMinuteKey(now);
}

static void drawImage()
{
  u8g2->clearBuffer();
  u8g2->setDrawColor(1);
  u8g2->drawXBMP(0, 0, LCD_WIDTH, LCD_HEIGHT, imageBuffer);
  u8g2->sendBuffer();
  hasSavedImage = true;
  currentPage = PAGE_IMAGE;
}

static uint8_t utf8CharLength(const char *text)
{
  uint8_t c = (uint8_t)text[0];
  if ((c & 0x80) == 0) {
    return 1;
  }
  if ((c & 0xe0) == 0xc0) {
    return 2;
  }
  if ((c & 0xf0) == 0xe0) {
    return 3;
  }
  if ((c & 0xf8) == 0xf0) {
    return 4;
  }
  return 1;
}

static void appendTextLine(String *lines, int &lineCount, const String &line)
{
  if (lineCount < TEXT_MAX_LINES) {
    lines[lineCount++] = line;
  }
}

static int textMaxLinesForScale(int scale)
{
  int maxLines = (LCD_HEIGHT - (TEXT_MARGIN_Y * 2)) / (TEXT_BASE_LINE_HEIGHT * scale);
  if (maxLines < 1) {
    maxLines = 1;
  }
  if (maxLines > TEXT_MAX_LINES) {
    maxLines = TEXT_MAX_LINES;
  }
  return maxLines;
}

static bool wrapUTF8Lines(const char *text, String *lines, int &lineCount, int maxWidth, int maxLines)
{
  String line;
  const char *cursor = text;
  lineCount = 0;

  while (*cursor != '\0' && lineCount < maxLines) {
    if (*cursor == '\r') {
      cursor++;
      continue;
    }
    if (*cursor == '\n') {
      appendTextLine(lines, lineCount, line);
      line = "";
      cursor++;
      continue;
    }

    uint8_t charLen = utf8CharLength(cursor);
    char chunk[5] = {0, 0, 0, 0, 0};
    for (uint8_t i = 0; i < charLen && cursor[i] != '\0'; i++) {
      chunk[i] = cursor[i];
    }

    String nextLine = line + chunk;
    if (line.length() > 0 && u8g2->getUTF8Width(nextLine.c_str()) > maxWidth) {
      appendTextLine(lines, lineCount, line);
      line = chunk;
      cursor += charLen;
      continue;
    }

    line = nextLine;
    cursor += charLen;
  }

  if ((line.length() > 0 || lineCount == 0) && lineCount < maxLines) {
    appendTextLine(lines, lineCount, line);
  }

  while (*cursor == '\r' || *cursor == '\n') {
    cursor++;
  }
  return *cursor == '\0';
}

static int layoutTextLines(const char *text, String *lines, int &lineCount)
{
  for (int scale = TEXT_MAX_SCALE; scale >= TEXT_MIN_SCALE; scale--) {
    int maxLines = textMaxLinesForScale(scale);
    bool fits = wrapUTF8Lines(text, lines, lineCount, TEXT_MAX_WIDTH / scale, maxLines);
    if (fits || scale == TEXT_MIN_SCALE) {
      return scale;
    }
  }
  return TEXT_MIN_SCALE;
}

static bool logicalBufferPixel(int x, int y)
{
  if (x < 0 || x >= LCD_WIDTH || y < 0 || y >= LCD_HEIGHT) {
    return false;
  }

  int bufferX = LCD_HEIGHT - 1 - y;
  int bufferY = x;
  uint8_t *buffer = u8g2->getBufferPtr();
  int tileWidth = u8g2->getBufferTileWidth();
  int offset = (bufferY & ~7) * tileWidth + bufferX;
  return (buffer[offset] & (1 << (bufferY & 7))) != 0;
}

static void drawScaledTextBitmap(int sourceX, int sourceY, int sourceWidth, int sourceHeight, int scale)
{
  size_t bitmapSize = (size_t)sourceWidth * (size_t)sourceHeight;
  uint8_t *bitmap = (uint8_t *)malloc(bitmapSize);
  if (bitmap == nullptr) {
    return;
  }

  for (int y = 0; y < sourceHeight; y++) {
    for (int x = 0; x < sourceWidth; x++) {
      bitmap[(size_t)y * sourceWidth + x] = logicalBufferPixel(sourceX + x, sourceY + y) ? 1 : 0;
    }
  }

  u8g2->clearBuffer();
  u8g2->setDrawColor(1);

  int scaledWidth = sourceWidth * scale;
  int scaledHeight = sourceHeight * scale;
  int destX = (LCD_WIDTH - scaledWidth) / 2;
  int destY = (LCD_HEIGHT - scaledHeight) / 2;
  if (destX < 0) {
    destX = 0;
  }
  if (destY < 0) {
    destY = 0;
  }

  for (int y = 0; y < sourceHeight; y++) {
    for (int x = 0; x < sourceWidth; x++) {
      if (bitmap[(size_t)y * sourceWidth + x] != 0) {
        u8g2->drawBox(destX + x * scale, destY + y * scale, scale, scale);
      }
    }
  }

  free(bitmap);
}

static void drawCenteredWrappedUTF8(const char *text)
{
  String lines[TEXT_MAX_LINES];
  int lineCount = 0;
  int scale = layoutTextLines(text, lines, lineCount);
  int ascent = u8g2->getAscent();
  int descent = u8g2->getDescent();
  int glyphHeight = ascent - descent;
  int sourceHeight = TEXT_SOURCE_TOP_PADDING + glyphHeight + (lineCount - 1) * TEXT_BASE_LINE_HEIGHT + TEXT_SOURCE_BOTTOM_PADDING;
  int sourceWidth = 0;

  for (int i = 0; i < lineCount; i++) {
    int lineWidth = u8g2->getUTF8Width(lines[i].c_str());
    if (lineWidth > sourceWidth) {
      sourceWidth = lineWidth;
    }
  }

  if (sourceWidth <= 0 || sourceHeight <= 0) {
    return;
  }
  if (sourceWidth > LCD_WIDTH - (TEXT_SOURCE_X * 2)) {
    sourceWidth = LCD_WIDTH - (TEXT_SOURCE_X * 2);
  }

  u8g2->clearBuffer();
  u8g2->setDrawColor(1);

  for (int i = 0; i < lineCount; i++) {
    int lineWidth = u8g2->getUTF8Width(lines[i].c_str());
    int x = TEXT_SOURCE_X + (sourceWidth - lineWidth) / 2;
    if (x < TEXT_SOURCE_X) {
      x = TEXT_SOURCE_X;
    }
    u8g2->drawUTF8(x, TEXT_SOURCE_Y + TEXT_SOURCE_TOP_PADDING + ascent + (i * TEXT_BASE_LINE_HEIGHT), lines[i].c_str());
  }

  drawScaledTextBitmap(TEXT_SOURCE_X, TEXT_SOURCE_Y, sourceWidth, sourceHeight, scale);
}

static void drawTextPage()
{
  u8g2->clearBuffer();
  u8g2->setDrawColor(1);
  u8g2->setFont(u8g2_font_wqy16_t_gb2312);
  drawCenteredWrappedUTF8(textBuffer);
  u8g2->sendBuffer();
  hasSavedText = true;
  currentPage = PAGE_TEXT;
}

static bool initStorage()
{
  storageReady = FFat.begin(true);
  if (!storageReady) {
    Serial.println("FFat mount failed");
    return false;
  }

  Serial.printf("FFat mounted, total=%lu free=%lu\n", (unsigned long)FFat.totalBytes(), (unsigned long)FFat.freeBytes());
  return true;
}

static bool loadLastImage()
{
  if (!storageReady || !FFat.exists(LAST_IMAGE_PATH)) {
    return false;
  }

  File file = FFat.open(LAST_IMAGE_PATH, FILE_READ);
  if (!file) {
    Serial.println("Could not open saved image");
    return false;
  }

  if (file.size() != IMAGE_BYTES) {
    Serial.printf("Saved image has wrong size: %lu\n", (unsigned long)file.size());
    file.close();
    FFat.remove(LAST_IMAGE_PATH);
    return false;
  }

  size_t readBytes = file.read(imageBuffer, IMAGE_BYTES);
  file.close();
  if (readBytes != IMAGE_BYTES) {
    Serial.printf("Saved image read failed: %lu\n", (unsigned long)readBytes);
    return false;
  }

  Serial.println("Loaded saved image from FFat");
  hasSavedImage = true;
  return true;
}

static bool saveLastImage()
{
  if (!storageReady) {
    notifyStatus("ERROR storage unavailable");
    return false;
  }

  File file = FFat.open(LAST_IMAGE_PATH, FILE_WRITE);
  if (!file) {
    notifyStatus("ERROR open image file");
    return false;
  }

  size_t written = file.write(imageBuffer, IMAGE_BYTES);
  file.close();
  if (written != IMAGE_BYTES) {
    notifyStatus("ERROR save image failed");
    return false;
  }

  hasSavedImage = true;
  notifyStatus("SAVED");
  return true;
}

static bool loadLastText()
{
  if (!storageReady || !FFat.exists(LAST_TEXT_PATH)) {
    return false;
  }

  File file = FFat.open(LAST_TEXT_PATH, FILE_READ);
  if (!file) {
    Serial.println("Could not open saved text");
    return false;
  }

  size_t textSize = file.size();
  if (textSize == 0 || textSize > MAX_TEXT_BYTES) {
    Serial.printf("Saved text has wrong size: %lu\n", (unsigned long)textSize);
    file.close();
    FFat.remove(LAST_TEXT_PATH);
    return false;
  }

  size_t readBytes = file.read((uint8_t *)textBuffer, textSize);
  file.close();
  if (readBytes != textSize) {
    Serial.printf("Saved text read failed: %lu\n", (unsigned long)readBytes);
    return false;
  }

  textBuffer[textSize] = '\0';
  Serial.println("Loaded saved text from FFat");
  hasSavedText = true;
  return true;
}

static bool saveLastText()
{
  if (!storageReady) {
    notifyStatus("ERROR storage unavailable");
    return false;
  }

  size_t textSize = strlen(textBuffer);
  if (textSize == 0 || textSize > MAX_TEXT_BYTES) {
    notifyStatus("ERROR bad text size");
    return false;
  }

  File file = FFat.open(LAST_TEXT_PATH, FILE_WRITE);
  if (!file) {
    notifyStatus("ERROR open text file");
    return false;
  }

  size_t written = file.write((const uint8_t *)textBuffer, textSize);
  file.close();
  if (written != textSize) {
    notifyStatus("ERROR save text failed");
    return false;
  }

  hasSavedText = true;
  notifyStatus("TEXT_SAVED");
  return true;
}

static void saveLastPage(DisplayPage page)
{
  if (!storageReady) {
    return;
  }

  File file = FFat.open(LAST_PAGE_PATH, FILE_WRITE);
  if (!file) {
    return;
  }
  char value = '0' + (uint8_t)page;
  file.write((const uint8_t *)&value, 1);
  file.close();
}

static DisplayPage loadLastPage()
{
  if (!storageReady || !FFat.exists(LAST_PAGE_PATH)) {
    return PAGE_IMAGE;
  }

  File file = FFat.open(LAST_PAGE_PATH, FILE_READ);
  if (!file) {
    return PAGE_IMAGE;
  }

  int value = file.read();
  file.close();
  if (value == '1') {
    return PAGE_IMAGE;
  }
  if (value == '2') {
    return PAGE_TEXT;
  }
  return PAGE_DEFAULT;
}

static void showPage(DisplayPage page, bool persist)
{
  if (page == PAGE_IMAGE) {
    if (hasSavedImage) {
      drawImage();
    } else {
      drawWaitingScreen("No image yet.");
      currentPage = PAGE_IMAGE;
    }
  } else if (page == PAGE_TEXT) {
    if (hasSavedText) {
      drawTextPage();
    } else {
      drawWaitingScreen("No text yet.");
      currentPage = PAGE_TEXT;
    }
  } else {
    drawDefaultPage();
  }

  if (persist) {
    saveLastPage(currentPage);
  }
}

static uint16_t readU16LE(const uint8_t *data)
{
  return (uint16_t)data[0] | ((uint16_t)data[1] << 8);
}

static uint32_t readU32LE(const uint8_t *data)
{
  return (uint32_t)data[0] | ((uint32_t)data[1] << 8) | ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24);
}

class ControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override
  {
    String value = characteristic->getValue();
    const uint8_t *data = (const uint8_t *)value.c_str();
    size_t len = value.length();

    if (len < 1) {
      return;
    }

    if (data[0] == 'S') {
      if (len != 9) {
        notifyStatus("ERROR bad START packet");
        return;
      }

      uint16_t width = readU16LE(data + 1);
      uint16_t height = readU16LE(data + 3);
      uint32_t totalBytes = readU32LE(data + 5);

      if (width != LCD_WIDTH || height != LCD_HEIGHT || totalBytes != IMAGE_BYTES) {
        notifyStatus("ERROR expected 400x300 1-bit image");
        return;
      }

      receiveMode = RECEIVE_IMAGE;
      imageReady = false;
      saveImagePending = false;
      receivedBytes = 0;
      expectedBytes = totalBytes;
      expectedSeq = 0;
      memset(imageBuffer, 0, sizeof(imageBuffer));
      notifyStatus("START 400x300 15000");
      drawWaitingScreen("Receiving image...");
      return;
    }

    if (data[0] == 'T') {
      if (len != 5) {
        notifyStatus("ERROR bad TEXT packet");
        return;
      }

      uint32_t totalBytes = readU32LE(data + 1);
      if (totalBytes == 0 || totalBytes > MAX_TEXT_BYTES) {
        notifyStatus("ERROR bad text length");
        return;
      }

      receiveMode = RECEIVE_TEXT;
      textReady = false;
      saveTextPending = false;
      receivedBytes = 0;
      expectedBytes = totalBytes;
      expectedSeq = 0;
      memset(textBuffer, 0, sizeof(textBuffer));
      char status[48];
      snprintf(status, sizeof(status), "TEXT_START %lu", (unsigned long)totalBytes);
      notifyStatus(status);
      drawWaitingScreen("Receiving text...");
      return;
    }

    if (data[0] == 'P') {
      if (len != 2 || data[1] > PAGE_TEXT) {
        notifyStatus("ERROR bad PAGE packet");
        return;
      }
      showPage((DisplayPage)data[1], true);
      if (data[1] == PAGE_IMAGE) {
        notifyStatus("PAGE_IMAGE");
      } else if (data[1] == PAGE_TEXT) {
        notifyStatus("PAGE_TEXT");
      } else {
        notifyStatus("PAGE_DEFAULT");
      }
      return;
    }

    if (data[0] == 'C') {
      if (len != 8) {
        notifyStatus("ERROR bad CLOCK packet");
        return;
      }
      uint16_t year = readU16LE(data + 1);
      setDashboardClock(year, data[3], data[4], data[5], data[6], data[7]);
      notifyStatus("CLOCK_SYNCED");
      return;
    }

    if (data[0] == 'A') {
      char progress[48];
      snprintf(progress, sizeof(progress), "RX %lu/%lu", (unsigned long)receivedBytes, (unsigned long)expectedBytes);
      notifyStatus(progress);
      return;
    }

    notifyStatus("ERROR unknown control command");
  }
};

class DataCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override
  {
    if (receiveMode == RECEIVE_NONE) {
      notifyStatus("ERROR data before START");
      return;
    }

    String value = characteristic->getValue();
    const uint8_t *data = (const uint8_t *)value.c_str();
    size_t len = value.length();

    if (len < 3) {
      notifyStatus("ERROR short data packet");
      receiveMode = RECEIVE_NONE;
      return;
    }

    uint16_t seq = readU16LE(data);
    if (seq != expectedSeq) {
      char error[48];
      snprintf(error, sizeof(error), "ERROR seq got %u expected %u", seq, expectedSeq);
      notifyStatus(error);
      receiveMode = RECEIVE_NONE;
      return;
    }

    size_t payloadLen = len - 2;
    if (receivedBytes + payloadLen > expectedBytes) {
      notifyStatus("ERROR payload too large");
      receiveMode = RECEIVE_NONE;
      return;
    }

    if (receiveMode == RECEIVE_IMAGE) {
      if (receivedBytes + payloadLen > IMAGE_BYTES) {
        notifyStatus("ERROR image too large");
        receiveMode = RECEIVE_NONE;
        return;
      }
      memcpy(imageBuffer + receivedBytes, data + 2, payloadLen);
    } else {
      if (receivedBytes + payloadLen > MAX_TEXT_BYTES) {
        notifyStatus("ERROR text too large");
        receiveMode = RECEIVE_NONE;
        return;
      }
      memcpy((uint8_t *)textBuffer + receivedBytes, data + 2, payloadLen);
    }

    receivedBytes += payloadLen;
    expectedSeq++;

    if ((expectedSeq % 20) == 0 || receivedBytes == expectedBytes) {
      char progress[48];
      snprintf(progress, sizeof(progress), "RX %lu/%lu", (unsigned long)receivedBytes, (unsigned long)expectedBytes);
      notifyStatus(progress);
    }

    if (receivedBytes == expectedBytes) {
      ReceiveMode completedMode = receiveMode;
      receiveMode = RECEIVE_NONE;
      if (completedMode == RECEIVE_IMAGE) {
        saveImagePending = true;
        imageReady = true;
        notifyStatus("DONE");
      } else {
        textBuffer[receivedBytes] = '\0';
        saveTextPending = true;
        textReady = true;
        notifyStatus("TEXT_DONE");
      }
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override
  {
    deviceConnected = true;
    notifyStatus("CONNECTED");
  }

  void onDisconnect(BLEServer *server) override
  {
    deviceConnected = false;
    receiveMode = RECEIVE_NONE;
    notifyStatus("DISCONNECTED");
    BLEDevice::startAdvertising();
  }
};

static void setupBle()
{
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(517);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  BLECharacteristic *controlCharacteristic = service->createCharacteristic(CONTROL_UUID, BLECharacteristic::PROPERTY_WRITE);
  BLECharacteristic *dataCharacteristic = service->createCharacteristic(DATA_UUID, BLECharacteristic::PROPERTY_WRITE);
  statusCharacteristic = service->createCharacteristic(
    STATUS_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );

  controlCharacteristic->setCallbacks(new ControlCallbacks());
  dataCharacteristic->setCallbacks(new DataCallbacks());
  statusCharacteristic->addDescriptor(new BLE2902());
  statusCharacteristic->setValue("READY");

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->setName(BLE_DEVICE_NAME);
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();
  notifyStatus("READY");
}

static void setupButtons()
{
  pinMode(BUTTON_KEY_PIN, INPUT_PULLUP);
  pinMode(BUTTON_BOOT_PIN, INPUT_PULLUP);
}

static void pollButton(ButtonTracker &button)
{
  bool down = digitalRead(button.pin) == LOW;
  uint32_t now = millis();

  if (down && !button.wasDown) {
    button.downAt = now;
    button.longHandled = false;
  }

  if (down && !button.longHandled && now - button.downAt >= BUTTON_LONG_PRESS_MS) {
    showPage(PAGE_DEFAULT, true);
    notifyStatus("PAGE_DEFAULT");
    button.longHandled = true;
  }

  if (!down && button.wasDown) {
    uint32_t pressedMs = now - button.downAt;
    if (!button.longHandled && pressedMs >= BUTTON_DEBOUNCE_MS) {
      showPage(button.shortPage, true);
      notifyStatus(button.shortPage == PAGE_IMAGE ? "PAGE_IMAGE" : "PAGE_TEXT");
    }
  }

  button.wasDown = down;
}

static void showBootPage(bool loadedImage, bool loadedText)
{
  DisplayPage page = loadLastPage();
  if (page == PAGE_TEXT && loadedText) {
    showPage(PAGE_TEXT, false);
    return;
  }
  if (page == PAGE_IMAGE && loadedImage) {
    showPage(PAGE_IMAGE, false);
    return;
  }
  if (page == PAGE_DEFAULT) {
    showPage(PAGE_DEFAULT, false);
    return;
  }
  if (loadedImage) {
    showPage(PAGE_IMAGE, false);
    return;
  }
  if (loadedText) {
    showPage(PAGE_TEXT, false);
    return;
  }
  showPage(PAGE_DEFAULT, false);
}

static void refreshDefaultPageIfNeeded()
{
  if (currentPage != PAGE_DEFAULT) {
    return;
  }

  DashboardDateTime now = dashboardNow();
  int minuteKey = dashboardMinuteKey(now);
  if (minuteKey != lastDashboardMinuteKey) {
    drawDefaultPage();
  }
}

void setup()
{
  Serial.begin(115200);
  delay(300);

  lcd.begin(0, U8G2_R1);
  u8g2 = lcd.getU8g2();
  u8g2->enableUTF8Print();
  initDashboardClock();
  drawWaitingScreen("Starting BLE...");

  setupButtons();
  initStorage();
  bool loadedImage = loadLastImage();
  bool loadedText = loadLastText();
  setupBle();
  showBootPage(loadedImage, loadedText);
}

void loop()
{
  pollButton(keyButton);
  pollButton(bootButton);
  refreshDefaultPageIfNeeded();

  if (imageReady) {
    imageReady = false;
    if (saveImagePending) {
      saveImagePending = false;
      notifyStatus("SAVING");
      saveLastImage();
    }
    notifyStatus("DRAWING");
    showPage(PAGE_IMAGE, true);
    notifyStatus("DISPLAYED");
  }

  if (textReady) {
    textReady = false;
    if (saveTextPending) {
      saveTextPending = false;
      notifyStatus("TEXT_SAVING");
      saveLastText();
    }
    notifyStatus("TEXT_DRAWING");
    showPage(PAGE_TEXT, true);
    notifyStatus("TEXT_DISPLAYED");
  }

  delay(20);
}
