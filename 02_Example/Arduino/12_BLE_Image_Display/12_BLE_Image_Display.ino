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

static void drawDefaultPage()
{
  u8g2->clearBuffer();
  u8g2->setDrawColor(1);
  u8g2->drawFrame(12, 12, 376, 276);
  u8g2->drawRFrame(26, 28, 348, 92, 12);
  u8g2->drawCircle(74, 74, 24);
  u8g2->drawCircle(74, 74, 12);
  u8g2->drawLine(50, 74, 98, 74);
  u8g2->drawLine(74, 50, 74, 98);

  u8g2->setFont(u8g2_font_10x20_tf);
  u8g2->drawStr(122, 68, "RLCD Ready");
  u8g2->setFont(u8g2_font_6x13_tf);
  u8g2->drawStr(124, 92, BLE_DEVICE_NAME);

  u8g2->drawHLine(42, 148, 316);
  u8g2->drawStr(52, 184, "KEY  : show image");
  u8g2->drawStr(52, 210, "BOOT : show text");
  u8g2->drawStr(52, 236, "Hold : home");
  u8g2->sendBuffer();
  currentPage = PAGE_DEFAULT;
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

static void drawWrappedUTF8(int x, int y, int maxWidth, int lineHeight, int maxLines, const char *text)
{
  String line;
  const char *cursor = text;
  int lines = 0;

  while (*cursor != '\0' && lines < maxLines) {
    if (*cursor == '\r') {
      cursor++;
      continue;
    }
    if (*cursor == '\n') {
      if (line.length() > 0) {
        u8g2->drawUTF8(x, y, line.c_str());
      }
      line = "";
      y += lineHeight;
      lines++;
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
      u8g2->drawUTF8(x, y, line.c_str());
      line = chunk;
      y += lineHeight;
      lines++;
      cursor += charLen;
      continue;
    }

    line = nextLine;
    cursor += charLen;
  }

  if (line.length() > 0 && lines < maxLines) {
    u8g2->drawUTF8(x, y, line.c_str());
  }
}

static void drawTextPage()
{
  u8g2->clearBuffer();
  u8g2->setDrawColor(1);
  u8g2->drawFrame(10, 10, 380, 280);
  u8g2->drawHLine(26, 48, 348);

  u8g2->setFont(u8g2_font_wqy14_t_gb2312);
  drawCenteredUTF8(35, "文字");
  drawWrappedUTF8(24, 76, 352, 21, 10, textBuffer);
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

void setup()
{
  Serial.begin(115200);
  delay(300);

  lcd.begin(0, U8G2_R1);
  u8g2 = lcd.getU8g2();
  u8g2->enableUTF8Print();
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
