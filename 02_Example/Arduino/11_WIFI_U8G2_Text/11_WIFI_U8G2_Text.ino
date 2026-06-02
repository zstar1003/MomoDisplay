#include <WiFi.h>
#include "ST7305_U8g2.h"

#define LCD_WIDTH 400
#define LCD_HEIGHT 300

#define RLCD_SCK_PIN 11
#define RLCD_MOSI_PIN 12
#define RLCD_DC_PIN 5
#define RLCD_CS_PIN 40
#define RLCD_RST_PIN 41

// Change these before uploading.
const char *WIFI_SSID = "your_ssid";
const char *WIFI_PASSWORD = "your_password";

static ST7305_U8g2 lcd(RLCD_SCK_PIN, RLCD_MOSI_PIN, RLCD_DC_PIN, RLCD_CS_PIN, RLCD_RST_PIN);
static U8G2 *u8g2 = nullptr;

static void drawCenteredStr(int y, const char *text)
{
  int text_width = u8g2->getStrWidth(text);
  int x = (LCD_WIDTH - text_width) / 2;
  if (x < 0) {
    x = 0;
  }
  u8g2->drawStr(x, y, text);
}

static void drawBird(int x, int y, int size)
{
  u8g2->drawLine(x - size, y, x, y - size / 2);
  u8g2->drawLine(x, y - size / 2, x + size, y);
}

static void drawPineTree(int x, int y, int height)
{
  int width = height / 2;
  u8g2->drawTriangle(x, y - height, x - width, y - height / 2, x + width, y - height / 2);
  u8g2->drawTriangle(x, y - height * 3 / 4, x - width * 3 / 4, y - height / 4, x + width * 3 / 4, y - height / 4);
  u8g2->drawTriangle(x, y - height / 2, x - width / 2, y, x + width / 2, y);
  u8g2->drawBox(x - 2, y, 4, height / 5);
}

static void drawCabin(int x, int y)
{
  u8g2->drawTriangle(x - 36, y, x, y - 32, x + 36, y);
  u8g2->drawLine(x - 44, y + 2, x, y - 38);
  u8g2->drawLine(x + 44, y + 2, x, y - 38);
  u8g2->drawFrame(x - 30, y, 60, 42);
  u8g2->drawFrame(x - 8, y + 17, 16, 25);
  u8g2->drawFrame(x - 24, y + 12, 14, 12);
  u8g2->drawFrame(x + 10, y + 12, 14, 12);
  u8g2->drawBox(x + 18, y - 28, 8, 18);
  u8g2->drawLine(x + 18, y - 28, x + 28, y - 34);
  u8g2->drawLine(x + 28, y - 34, x + 30, y - 28);
}

static void drawStartupArt(const char *status, const char *detail)
{
  u8g2->clearBuffer();
  u8g2->setDrawColor(1);

  u8g2->drawFrame(8, 8, 384, 284);
  u8g2->drawFrame(14, 14, 372, 272);

  u8g2->drawDisc(316, 58, 24);
  u8g2->setDrawColor(0);
  u8g2->drawDisc(306, 50, 24);
  u8g2->setDrawColor(1);

  drawBird(78, 56, 10);
  drawBird(112, 70, 7);
  drawBird(262, 86, 8);

  u8g2->drawLine(22, 168, 108, 72);
  u8g2->drawLine(108, 72, 176, 168);
  u8g2->drawLine(132, 168, 220, 86);
  u8g2->drawLine(220, 86, 322, 168);
  u8g2->drawLine(260, 168, 340, 104);
  u8g2->drawLine(340, 104, 378, 168);

  u8g2->drawLine(87, 95, 110, 119);
  u8g2->drawLine(110, 119, 128, 98);
  u8g2->drawLine(198, 107, 220, 131);
  u8g2->drawLine(220, 131, 246, 108);
  u8g2->drawLine(325, 116, 340, 132);
  u8g2->drawLine(340, 132, 354, 116);

  u8g2->drawHLine(18, 169, 364);
  u8g2->drawHLine(28, 185, 344);

  drawPineTree(44, 183, 48);
  drawPineTree(72, 182, 36);
  drawPineTree(356, 184, 44);
  drawPineTree(330, 182, 34);
  drawCabin(202, 152);

  u8g2->drawHLine(34, 220, 332);
  u8g2->drawLine(64, 228, 154, 220);
  u8g2->drawLine(246, 220, 336, 228);
  u8g2->drawHLine(90, 238, 220);
  u8g2->drawHLine(58, 253, 86);
  u8g2->drawHLine(214, 253, 120);

  u8g2->drawLine(174, 220, 160, 246);
  u8g2->drawLine(230, 220, 244, 246);
  u8g2->drawLine(160, 246, 244, 246);

  u8g2->setFont(u8g2_font_6x13_tf);
  u8g2->drawHLine(28, 256, 344);
  drawCenteredStr(270, status);
  if (detail != nullptr && detail[0] != '\0') {
    u8g2->drawStr(28, 284, detail);
  }

  u8g2->sendBuffer();
}

static void connectWifi()
{
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.printf("Connecting to WiFi SSID: %s\n", WIFI_SSID);
  drawStartupArt("WiFi: connecting...", WIFI_SSID);

  uint32_t start_ms = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start_ms < 20000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    String ip = WiFi.localIP().toString();
    Serial.printf("WiFi connected, IP: %s\n", ip.c_str());
    drawStartupArt("WiFi: connected", ip.c_str());
  } else {
    Serial.println("WiFi connection failed");
    drawStartupArt("WiFi: failed", "Check SSID/password and 2.4GHz network");
  }
}

void setup()
{
  Serial.begin(115200);
  delay(300);

  lcd.begin(0, U8G2_R1);
  u8g2 = lcd.getU8g2();

  drawStartupArt("Booting...", "");
  connectWifi();
}

void loop()
{
  if (WiFi.status() != WL_CONNECTED) {
    connectWifi();
  }

  delay(5000);
}
