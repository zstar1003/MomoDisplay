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

#define RLCD_SCK_PIN 11
#define RLCD_MOSI_PIN 12
#define RLCD_DC_PIN 5
#define RLCD_CS_PIN 40
#define RLCD_RST_PIN 41

static const char *BLE_DEVICE_NAME = "RLCD-BLE-IMG";
static const char *SERVICE_UUID = "7f6b0001-5f02-4fd8-9f23-6f8e4c59a001";
static const char *CONTROL_UUID = "7f6b0002-5f02-4fd8-9f23-6f8e4c59a001";
static const char *DATA_UUID = "7f6b0003-5f02-4fd8-9f23-6f8e4c59a001";
static const char *STATUS_UUID = "7f6b0004-5f02-4fd8-9f23-6f8e4c59a001";
static const char *LAST_IMAGE_PATH = "/last_image.bin";

static ST7305_U8g2 lcd(RLCD_SCK_PIN, RLCD_MOSI_PIN, RLCD_DC_PIN, RLCD_CS_PIN, RLCD_RST_PIN);
static U8G2 *u8g2 = nullptr;

static BLECharacteristic *statusCharacteristic = nullptr;
static uint8_t imageBuffer[IMAGE_BYTES];
static volatile bool imageReady = false;
static volatile bool deviceConnected = false;
static volatile uint32_t expectedBytes = 0;
static volatile uint32_t receivedBytes = 0;
static volatile uint16_t expectedSeq = 0;
static volatile bool receiving = false;
static volatile bool savePending = false;
static bool storageReady = false;
static bool hasDisplayedImage = false;

static void drawCenteredStr(int y, const char *text)
{
  int textWidth = u8g2->getStrWidth(text);
  int x = (LCD_WIDTH - textWidth) / 2;
  if (x < 0) {
    x = 0;
  }
  u8g2->drawStr(x, y, text);
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
  drawCenteredStr(214, "RLCD BLE Image");
  u8g2->setFont(u8g2_font_6x13_tf);
  drawCenteredStr(246, status);
  drawCenteredStr(266, BLE_DEVICE_NAME);
  u8g2->sendBuffer();
}

static void drawImage()
{
  u8g2->clearBuffer();
  u8g2->setDrawColor(1);
  u8g2->drawXBMP(0, 0, LCD_WIDTH, LCD_HEIGHT, imageBuffer);
  u8g2->sendBuffer();
  hasDisplayedImage = true;
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

  notifyStatus("SAVED");
  return true;
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

      receiving = true;
      imageReady = false;
      savePending = false;
      receivedBytes = 0;
      expectedBytes = totalBytes;
      expectedSeq = 0;
      memset(imageBuffer, 0, sizeof(imageBuffer));
      notifyStatus("START 400x300 15000");
      drawWaitingScreen("Receiving image...");
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
    if (!receiving) {
      notifyStatus("ERROR data before START");
      return;
    }

    String value = characteristic->getValue();
    const uint8_t *data = (const uint8_t *)value.c_str();
    size_t len = value.length();

    if (len < 3) {
      notifyStatus("ERROR short data packet");
      receiving = false;
      return;
    }

    uint16_t seq = readU16LE(data);
    if (seq != expectedSeq) {
      char error[48];
      snprintf(error, sizeof(error), "ERROR seq got %u expected %u", seq, expectedSeq);
      notifyStatus(error);
      receiving = false;
      return;
    }

    size_t payloadLen = len - 2;
    if (receivedBytes + payloadLen > IMAGE_BYTES) {
      notifyStatus("ERROR image too large");
      receiving = false;
      return;
    }

    memcpy(imageBuffer + receivedBytes, data + 2, payloadLen);
    receivedBytes += payloadLen;
    expectedSeq++;

    if ((expectedSeq % 20) == 0 || receivedBytes == expectedBytes) {
      char progress[48];
      snprintf(progress, sizeof(progress), "RX %lu/%lu", (unsigned long)receivedBytes, (unsigned long)expectedBytes);
      notifyStatus(progress);
    }

    if (receivedBytes == expectedBytes) {
      receiving = false;
      savePending = true;
      imageReady = true;
      notifyStatus("DONE");
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override
  {
    deviceConnected = true;
    notifyStatus("CONNECTED");
    if (!hasDisplayedImage) {
      drawWaitingScreen("Connected. Select image in app.");
    }
  }

  void onDisconnect(BLEServer *server) override
  {
    deviceConnected = false;
    receiving = false;
    notifyStatus("DISCONNECTED");
    BLEDevice::startAdvertising();
    if (!hasDisplayedImage) {
      drawWaitingScreen("Waiting for BLE connection...");
    }
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

void setup()
{
  Serial.begin(115200);
  delay(300);

  lcd.begin(0, U8G2_R1);
  u8g2 = lcd.getU8g2();
  drawWaitingScreen("Starting BLE...");

  initStorage();
  bool loadedImage = loadLastImage();
  setupBle();
  if (loadedImage) {
    drawImage();
    notifyStatus("READY saved image displayed");
  } else {
    drawWaitingScreen("Waiting for BLE connection...");
  }
}

void loop()
{
  if (imageReady) {
    imageReady = false;
    if (savePending) {
      savePending = false;
      notifyStatus("SAVING");
      saveLastImage();
    }
    notifyStatus("DRAWING");
    drawImage();
    notifyStatus("DISPLAYED");
  }

  delay(20);
}
