import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Buckshot-style Roulette (First Person, Swing, English)
 *
 * Controls:
 *  - LEFT CLICK on table: start shot (target selection appears)
 *  - Click "SHOOT SELF" / "SHOOT DEALER": fire
 *
 *  - Click CIGAR: +1 HP (once per cylinder)
 *  - Click BEER: reverse cylinder (once per cylinder)
 *  - Click SANDPAPER: make next bullet live (once per cylinder)
 *
 *  - ENTER: start from menu
 *  - SPACE / ESC: pause / unpause
 *  - R: restart after win/lose
 *
 * Logic:
 *  - HP does NOT reset.
 *  - When cylinder is empty, it is simply refilled (new random bullets).
 *  - Game ends ONLY when player HP <= 0 or dealer HP <= 0.
 */
public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(GameFrame::new);
    }
}

// ============================================================
//                       WINDOW
// ============================================================

class GameFrame extends JFrame {

    GamePanel panel;

    GameFrame() {
        setTitle("Buckshot FP (Swing)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setUndecorated(true);                 // borderless
        setExtendedState(JFrame.MAXIMIZED_BOTH); // fullscreen

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        panel = new GamePanel(screen.width, screen.height);
        add(panel);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        panel.startGameLoop();
    }
}

// ============================================================
//                       GAME PANEL
// ============================================================

class GamePanel extends JPanel implements MouseListener, MouseMotionListener, KeyListener, Runnable {

    enum State {
        MENU, PLAYING, PAUSED, WIN, LOSE, SELECT_TARGET
    }

    int width;
    int height;

    Thread gameThread;
    boolean running = false;

    State state = State.MENU;

    Random random = new Random();

    int playerHP = 3;
    int enemyHP = 3;

    List<AmmoSlot> ammo = new ArrayList<>();
    int ammoIndex = 0;

    boolean cigarUsed = false;
    boolean beerUsed = false;
    boolean sandpaperUsed = false;

    String log = "WELCOME";

    // Camera / shake / breathing / drunk
    double shake = 0;
    double cameraBreathPhase = 0;
    double drunkPhase = 0;
    double drunkIntensity = 0;

    // Floating text
    List<FloatingText> floatingTexts = new ArrayList<>();

    // Menu animation
    double menuTitlePhase = 0;
    double menuFlash = 0;

    // Gun / hands
    double revolverRecoil = 0;
    double muzzleFlash = 0;
    double cylinderAngle = 0;

    // Fade overlay
    double fadeOverlay = 1.0;
    boolean fadeIn = true;

    // Light
    double lightPulsePhase = 0;
    double lightFlashTimer = 0;

    // Mouse
    int mouseX, mouseY;

    // Buttons
    RectButton startButton;
    RectButton quitButton;

    RectButton selfButton;
    RectButton dealerButton;

    // Items
    ItemIcon cigarIcon;
    ItemIcon beerIcon;
    ItemIcon sandpaperIcon;

    // Fonts
    Font fontSmall = new Font("Consolas", Font.PLAIN, 14);
    Font fontUI = new Font("Consolas", Font.PLAIN, 18);
    Font fontBig = new Font("Consolas", Font.BOLD, 42);
    Font fontTitle = new Font("Consolas", Font.BOLD, 52);

    GamePanel(int width, int height) {
        this.width = width;
        this.height = height;

        setPreferredSize(new Dimension(width, height));
        setFocusable(true);
        requestFocusInWindow();

        addMouseListener(this);
        addMouseMotionListener(this);
        addKeyListener(this);

        startButton = new RectButton("START", width / 2 - 120, height / 2 + 40, 240, 60);
        quitButton = new RectButton("QUIT", width / 2 - 120, height / 2 + 120, 240, 60);

        int tableY = (int) (height * 0.6);

        selfButton = new RectButton("SHOOT SELF", width / 2 - 220, tableY - 160, 180, 60);
        dealerButton = new RectButton("SHOOT DEALER", width / 2 + 40, tableY - 160, 180, 60);

        cigarIcon = new ItemIcon(ItemIcon.Type.CIGAR, width - 220, tableY + 40, 60, 40);
        beerIcon = new ItemIcon(ItemIcon.Type.BEER, width - 320, tableY + 40, 60, 40);
        sandpaperIcon = new ItemIcon(ItemIcon.Type.SANDPAPER, width - 420, tableY + 40, 60, 40);

        refillCylinder();
    }

    // ============================================================
    //                       GAME LOOP
    // ============================================================

    void startGameLoop() {
        if (gameThread != null && gameThread.isAlive()) return;
        running = true;
        gameThread = new Thread(this);
        gameThread.start();
    }

    @Override
    public void run() {
        long lastTime = System.nanoTime();
        double nsPerUpdate = 1_000_000_000.0 / 60.0; // 60 FPS

        double delta = 0;

        while (running) {
            long now = System.nanoTime();
            delta += (now - lastTime) / nsPerUpdate;
            lastTime = now;

            while (delta >= 1) {
                updateGame();
                delta--;
            }

            repaint();

            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ============================================================
    //                       GAME LOGIC
    // ============================================================

    void resetGame() {
        playerHP = 3;
        enemyHP = 3;
        cigarUsed = false;
        beerUsed = false;
        sandpaperUsed = false;
        state = State.MENU;
        log = "WELCOME";
        fadeOverlay = 1.0;
        fadeIn = true;
        refillCylinder();
    }

    void refillCylinder() {
        ammo.clear();
        ammoIndex = 0;

        int count = random.nextInt(5) + 4; // 4–8 bullets
        for (int i = 0; i < count; i++) {
            ammo.add(new AmmoSlot(random.nextBoolean()));
        }

        cigarUsed = false;
        beerUsed = false;
        sandpaperUsed = false;

        log = "NEW CYLINDER";
        addFloatingText("NEW CYLINDER", width / 2.0, height * 0.18, new Color(255, 215, 0));
        fadeOverlay = 1.0;
        fadeIn = true;
    }

    boolean isEnd() {
        return state == State.WIN || state == State.LOSE;
    }

    void checkEnd() {
        if (playerHP <= 0 && enemyHP <= 0) {
            state = State.LOSE;
            log = "BOTH DEAD";
        } else if (playerHP <= 0) {
            state = State.LOSE;
            log = "YOU LOSE";
        } else if (enemyHP <= 0) {
            state = State.WIN;
            log = "YOU WIN";
        }
    }

    void togglePause() {
        if (state == State.PLAYING) state = State.PAUSED;
        else if (state == State.PAUSED) state = State.PLAYING;
    }

    void startFromMenu() {
        if (state == State.MENU) {
            state = State.PLAYING;
            fadeOverlay = 1.0;
            fadeIn = true;
            log = "GAME START";
        }
    }

    // ============================================================
    //                       PLAYER / AI ACTIONS
    // ============================================================

    void requestPlayerShot() {
        if (state != State.PLAYING) return;
        if (isEnd()) return;

        if (ammoIndex >= ammo.size()) {
            refillCylinder();
            return;
        }

        state = State.SELECT_TARGET;
    }

    void playerShoot(boolean targetSelf) {
        if (state != State.SELECT_TARGET) return;
        if (isEnd()) return;

        if (ammoIndex >= ammo.size()) {
            refillCylinder();
            state = State.PLAYING;
            return;
        }

        AmmoSlot slot = ammo.get(ammoIndex++);
        slot.used = true;

        revolverRecoil = 1.0;
        muzzleFlash = 1.0;
        cylinderAngle += Math.PI / 3;

        if (slot.live) {
            int dmg = 1;
            if (targetSelf) {
                playerHP -= dmg;
                shake = 25;
                log = "YOU SHOT YOURSELF -" + dmg;
                addFloatingText("-" + dmg, width / 2.0, height * 0.8, new Color(255, 50, 50));
            } else {
                enemyHP -= dmg;
                shake = 20;
                log = "YOU SHOT DEALER -" + dmg;
                addFloatingText("-" + dmg, width / 2.0, height * 0.25, new Color(255, 80, 80));
            }
            lightFlashTimer = 0.25;
        } else {
            log = "CLICK";
            addFloatingText("CLICK", width / 2.0, height * 0.5, new Color(200, 200, 200));
        }

        checkEnd();

        if (!isEnd()) {
            state = State.PLAYING;
            aiTurn();
        }
    }

    void aiTurn() {
        if (isEnd()) return;

        if (ammoIndex >= ammo.size()) {
            refillCylinder();
            return;
        }

        AmmoSlot slot = ammo.get(ammoIndex++);
        slot.used = true;

        revolverRecoil = 0.7;
        muzzleFlash = 0.8;
        cylinderAngle += Math.PI / 3;

        // Simple AI:
        // if bullet is live -> shoot player
        // if bullet is blank -> shoot self
        if (slot.live) {
            int dmg = 1;
            playerHP -= dmg;
            shake = 25;
            log = "DEALER SHOT YOU -" + dmg;
            addFloatingText("-" + dmg, width / 2.0, height * 0.8, new Color(255, 50, 50));
            lightFlashTimer = 0.25;
        } else {
            int dmg = 1;
            enemyHP -= dmg;
            shake = 20;
            log = "DEALER SHOT HIMSELF -" + dmg;
            addFloatingText("-" + dmg, width / 2.0, height * 0.25, new Color(255, 80, 80));
        }

        checkEnd();
    }

    void useCigar() {
        if (state != State.PLAYING && state != State.SELECT_TARGET) return;
        if (cigarUsed || isEnd()) return;

        playerHP++;
        cigarUsed = true;
        log = "+1 HP (CIGAR)";
        addFloatingText("+1 HP", width / 2.0, height * 0.78, new Color(120, 255, 120));
    }

    void useBeer() {
        if (state != State.PLAYING && state != State.SELECT_TARGET) return;
        if (beerUsed || isEnd()) return;
        if (ammo.isEmpty()) return;

        beerUsed = true;
        log = "BEER: CYLINDER REVERSED";
        addFloatingText("BEER!", width / 2.0, height * 0.5, new Color(180, 200, 255));

        List<AmmoSlot> reversed = new ArrayList<>();
        for (int i = ammo.size() - 1; i >= 0; i--) {
            reversed.add(ammo.get(i));
        }
        ammo = reversed;
        ammoIndex = 0;

        drunkIntensity = 1.0;
    }

    void useSandpaper() {
        if (state != State.PLAYING && state != State.SELECT_TARGET) return;
        if (sandpaperUsed || isEnd()) return;
        if (ammoIndex >= ammo.size()) return;

        sandpaperUsed = true;
        log = "SANDPAPER: NEXT BULLET LIVE";
        addFloatingText("SANDPAPER", width / 2.0, height * 0.5, new Color(220, 220, 220));

        ammo.get(ammoIndex).live = true;
    }

    // ============================================================
    //                       UPDATE
    // ============================================================

    void updateGame() {
        cameraBreathPhase += 0.02;
        if (state == State.PLAYING || state == State.SELECT_TARGET) {
            shake *= 0.85;
        } else {
            shake *= 0.9;
        }

        if (drunkIntensity > 0) {
            drunkPhase += 0.08;
            drunkIntensity -= 0.005;
            if (drunkIntensity < 0) drunkIntensity = 0;
        }

        if (state == State.MENU) {
            menuTitlePhase += 0.05;
            menuFlash += 0.03;
        }

        if (revolverRecoil > 0) {
            revolverRecoil -= 0.08;
            if (revolverRecoil < 0) revolverRecoil = 0;
        }
        if (muzzleFlash > 0) {
            muzzleFlash -= 0.15;
            if (muzzleFlash < 0) muzzleFlash = 0;
        }

        if (fadeIn) {
            fadeOverlay -= 0.05;
            if (fadeOverlay <= 0) {
                fadeOverlay = 0;
                fadeIn = false;
            }
        } else {
            if (isEnd()) {
                if (fadeOverlay < 0.6) {
                    fadeOverlay += 0.01;
                }
            }
        }

        lightPulsePhase += 0.02;
        if (lightFlashTimer > 0) {
            lightFlashTimer -= 1.0 / 60.0;
            if (lightFlashTimer < 0) lightFlashTimer = 0;
        }

        List<FloatingText> toRemove = new ArrayList<>();
        for (FloatingText ft : floatingTexts) {
            ft.update();
            if (!ft.alive) toRemove.add(ft);
        }
        floatingTexts.removeAll(toRemove);

        startButton.update(mouseX, mouseY);
        quitButton.update(mouseX, mouseY);
        selfButton.update(mouseX, mouseY);
        dealerButton.update(mouseX, mouseY);

        cigarIcon.update(mouseX, mouseY, !cigarUsed);
        beerIcon.update(mouseX, mouseY, !beerUsed);
        sandpaperIcon.update(mouseX, mouseY, !sandpaperUsed);
    }

    // ============================================================
    //                       RENDER
    // ============================================================

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawBackground(g);

        double breathX = Math.sin(cameraBreathPhase) * 2;
        double breathY = Math.cos(cameraBreathPhase * 0.7) * 2;

        double sx = (random.nextDouble() - 0.5) * shake + breathX;
        double sy = (random.nextDouble() - 0.5) * shake + breathY;

        double drunkX = Math.sin(drunkPhase) * 10 * drunkIntensity;
        double drunkY = Math.cos(drunkPhase * 0.7) * 8 * drunkIntensity;

        AffineTransform old = g.getTransform();
        g.translate(sx + drunkX, sy + drunkY);

        drawTable(g);
        drawEnemySilhouette(g);
        drawHandsAndGun(g);
        drawAmmo(g);
        drawItems(g);

        if (state == State.MENU) {
            drawMenu(g);
        } else {
            drawTopUI(g);
        }

        if (state == State.SELECT_TARGET) {
            drawTargetSelection(g);
        }

        for (FloatingText ft : floatingTexts) {
            ft.draw(g);
        }

        if (state == State.PAUSED) {
            drawOverlay(g, "PAUSED", "SPACE / ESC - CONTINUE");
        }
        if (state == State.WIN) {
            drawOverlay(g, "YOU WIN", "R - RESTART");
        }
        if (state == State.LOSE) {
            drawOverlay(g, "YOU LOSE", "R - RESTART");
        }

        if (fadeOverlay > 0) {
            g.setColor(new Color(0, 0, 0, (float) Math.min(1.0, fadeOverlay)));
            g.fillRect(0, 0, width, height);
        }

        g.setTransform(old);
        g.dispose();
    }

    void drawBackground(Graphics2D g) {
        float pulse = (float) ((Math.sin(lightPulsePhase) + 1) / 2.0);
        float flash = (float) lightFlashTimer;

        for (int y = 0; y < height; y++) {
            float t = (float) y / height;
            Color baseTop = new Color(5, 5, 15);
            Color baseBottom = new Color(0, 0, 0);

            Color c = blend(baseTop, baseBottom, t);

            int extra = (int) (pulse * 10 + flash * 80);
            int r = Math.min(255, c.getRed() + extra);
            int gr = Math.min(255, c.getGreen() + extra);
            int b = Math.min(255, c.getBlue() + extra);

            g.setColor(new Color(r, gr, b));
            g.drawLine(0, y, width, y);
        }
    }

    void drawTable(Graphics2D g) {
        int tableY = (int) (height * 0.6);

        g.setColor(new Color(30, 18, 10));
        g.fillRect(0, tableY, width, height - tableY);

        g.setColor(new Color(80, 55, 35));
        g.fillRect(0, tableY - 3, width, 3);

        g.setColor(new Color(255, 255, 255, 20));
        g.fillOval(width / 2 - 260, tableY - 120, 520, 240);
    }

    void drawEnemySilhouette(Graphics2D g) {
        int centerX = width / 2;
        int baseY = (int) (height * 0.25);

        int bodyWidth = 160;
        int bodyHeight = 220;

        g.setColor(new Color(0, 0, 0, 160));
        g.fillOval(centerX - 80, baseY + 80, 160, 40);

        g.setColor(new Color(15, 15, 15, 230));
        g.fillRoundRect(centerX - bodyWidth / 2, baseY - bodyHeight / 2, bodyWidth, bodyHeight, 40, 40);

        g.fillOval(centerX - 50, baseY - bodyHeight / 2 - 70, 100, 100);

        g.setColor(new Color(255, 255, 255, 40));
        g.fillOval(centerX - 40, baseY - bodyHeight / 2 - 60, 80, 40);
    }

    void drawHandsAndGun(Graphics2D g) {
        int tableY = (int) (height * 0.6);

        int centerX = width / 2;
        int baseY = tableY + 80;

        double recoilOffset = revolverRecoil * 22;

        Graphics2D gl = (Graphics2D) g.create();
        gl.translate(centerX - 120, baseY + 40);
        gl.rotate(Math.toRadians(12));

        gl.setColor(new Color(15, 15, 15, 230));
        gl.fillRoundRect(-60, -20, 120, 40, 20, 20);

        gl.setColor(new Color(25, 25, 25, 230));
        gl.fillRoundRect(40, -30, 60, 60, 20, 20);

        gl.dispose();

        Graphics2D gr = (Graphics2D) g.create();
        gr.translate(centerX + 60, baseY + 40 - recoilOffset * 0.5);
        gr.rotate(Math.toRadians(-18));

        gr.setColor(new Color(15, 15, 15, 230));
        gr.fillRoundRect(-80, -20, 140, 40, 20, 20);

        gr.setColor(new Color(25, 25, 25, 230));
        gr.fillRoundRect(40, -30, 60, 60, 20, 20);

        gr.dispose();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(centerX, baseY - recoilOffset);
        g2.rotate(-Math.toRadians(10));

        g2.setColor(new Color(40, 28, 18));
        g2.fillRoundRect(-90, -25, 70, 50, 25, 25);

        g2.setColor(new Color(60, 60, 70));
        g2.fillRoundRect(-40, -18, 130, 36, 18, 18);

        g2.fillRoundRect(50, -12, 110, 24, 12, 12);

        Graphics2D gCyl = (Graphics2D) g2.create();
        gCyl.translate(-5, 0);
        gCyl.rotate(cylinderAngle);
        gCyl.setColor(new Color(80, 80, 90));
        gCyl.fillOval(-22, -22, 44, 44);
        gCyl.setColor(new Color(40, 40, 50));
        gCyl.drawOval(-22, -22, 44, 44);
        gCyl.setColor(new Color(20, 20, 25));
        gCyl.fillOval(-8, -8, 16, 16);
        gCyl.dispose();

        if (muzzleFlash > 0) {
            float a = (float) Math.min(1.0, muzzleFlash);
            g2.setColor(new Color(255, 230, 150, (int) (a * 255)));
            Polygon flash = new Polygon();
            flash.addPoint(160, 0);
            flash.addPoint(200, -20);
            flash.addPoint(240, 0);
            flash.addPoint(200, 20);
            g2.fillPolygon(flash);
        }

        g2.dispose();
    }

    void drawAmmo(Graphics2D g) {
        int tableY = (int) (height * 0.6);
        double startX = width * 0.1;
        double y = tableY - 60;
        double w = 26;
        double h = 44;
        double gap = 12;

        for (int i = 0; i < ammo.size(); i++) {
            AmmoSlot slot = ammo.get(i);
            double x = startX + i * (w + gap);

            Color bodyColor;
            if (slot.used) {
                bodyColor = slot.live ? new Color(120, 20, 20) : new Color(60, 60, 60);
            } else {
                bodyColor = slot.live ? new Color(200, 40, 40) : new Color(120, 120, 120);
            }

            g.setColor(new Color(0, 0, 0, 80));
            g.fillOval((int) x - 2, (int) y + (int) h - 4, (int) w + 4, 8);

            g.setColor(bodyColor);
            g.fillRoundRect((int) x, (int) y, (int) w, (int) h, 10, 10);

            g.setColor(new Color(210, 170, 60));
            g.fillRoundRect((int) x, (int) (y + h - 14), (int) w, 14, 10, 10);

            g.setColor(new Color(0, 0, 0, 180));
            g.drawRoundRect((int) x, (int) y, (int) w, (int) h, 10, 10);
        }
    }

    void drawItems(Graphics2D g) {
        cigarIcon.draw(g, !cigarUsed);
        beerIcon.draw(g, !beerUsed);
        sandpaperIcon.draw(g, !sandpaperUsed);
    }

    void drawTopUI(Graphics2D g) {
        g.setFont(fontUI);
        g.setColor(Color.WHITE);

        String hpText = "YOU: " + Math.max(playerHP, 0) + "   DEALER: " + Math.max(enemyHP, 0);
        g.drawString(hpText, 20, 40);

        g.setFont(fontSmall);
        g.setColor(new Color(200, 200, 200));
        g.drawString("CLICK TABLE: SHOOT (THEN CHOOSE TARGET)", 20, 70);
        g.drawString("CLICK ITEMS: CIGAR (+HP), BEER (REVERSE), SANDPAPER (NEXT LIVE)", 20, 90);

        g.setColor(new Color(255, 215, 0));
        g.drawString("LOG: " + log, 20, 120);
    }

    void drawMenu(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, width, height);

        g.setFont(fontTitle);
        float t = (float) ((Math.sin(menuTitlePhase) + 1) / 2.0);
        Color titleColor = blend(new Color(255, 255, 255), new Color(255, 220, 120), t);
        g.setColor(titleColor);

        String title = "BUCKSHOT NIGHT";
        int tw = g.getFontMetrics().stringWidth(title);
        g.drawString(title, width / 2 - tw / 2, height / 2 - 120);

        g.setFont(fontSmall);
        g.setColor(new Color(200, 200, 200));
        String sub = "First-person roulette. One cylinder. One winner.";
        int sw = g.getFontMetrics().stringWidth(sub);
        g.drawString(sub, width / 2 - sw / 2, height / 2 - 90);

        startButton.draw(g);
        quitButton.draw(g);

        g.setFont(fontSmall);
        float f = (float) ((Math.sin(menuFlash) + 1) / 2.0);
        g.setColor(new Color(255, 255, 255, (int) (120 + 80 * f)));
        String hint = "PRESS ENTER OR CLICK START";
        int hw = g.getFontMetrics().stringWidth(hint);
        g.drawString(hint, width / 2 - hw / 2, height - 60);
    }

    void drawTargetSelection(Graphics2D g) {
        int tableY = (int) (height * 0.6);

        g.setColor(new Color(0, 0, 0, 180));
        g.fillRoundRect(width / 2 - 260, tableY - 200, 520, 140, 30, 30);

        g.setFont(fontUI);
        g.setColor(Color.WHITE);
        String txt = "CHOOSE TARGET";
        int tw = g.getFontMetrics().stringWidth(txt);
        g.drawString(txt, width / 2 - tw / 2, tableY - 170);

        selfButton.draw(g);
        dealerButton.draw(g);
    }

    void drawOverlay(Graphics2D g, String title, String subtitle) {
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, width, height);

        g.setFont(fontBig);
        g.setColor(Color.WHITE);
        int tw = g.getFontMetrics().stringWidth(title);
        g.drawString(title, width / 2 - tw / 2, height / 2 - 10);

        g.setFont(fontSmall);
        g.setColor(new Color(200, 200, 200));
        int sw = g.getFontMetrics().stringWidth(subtitle);
        g.drawString(subtitle, width / 2 - sw / 2, height / 2 + 20);
    }

    // ============================================================
    //                       FLOATING TEXT
    // ============================================================

    void addFloatingText(String text, double x, double y, Color color) {
        floatingTexts.add(new FloatingText(text, x, y, color));
    }

    // ============================================================
    //                       UTILS
    // ============================================================

    Color blend(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (a.getRed() * (1 - t) + b.getRed() * t);
        int g = (int) (a.getGreen() * (1 - t) + b.getGreen() * t);
        int bl = (int) (a.getBlue() * (1 - t) + b.getBlue() * t);
        return new Color(r, g, bl);
    }

    // ============================================================
    //                       INPUT
    // ============================================================

    @Override
    public void mouseClicked(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();

        if (state == State.MENU) {
            if (startButton.contains(mouseX, mouseY)) {
                startFromMenu();
            } else if (quitButton.contains(mouseX, mouseY)) {
                System.exit(0);
            }
            return;
        }

        if (state == State.WIN || state == State.LOSE) return;

        int tableY = (int) (height * 0.6);

        if (state == State.SELECT_TARGET) {
            if (selfButton.contains(mouseX, mouseY)) {
                playerShoot(true);
                return;
            }
            if (dealerButton.contains(mouseX, mouseY)) {
                playerShoot(false);
                return;
            }
        }

        if (cigarIcon.contains(mouseX, mouseY) && !cigarUsed) {
            useCigar();
            return;
        }
        if (beerIcon.contains(mouseX, mouseY) && !beerUsed) {
            useBeer();
            return;
        }
        if (sandpaperIcon.contains(mouseX, mouseY) && !sandpaperUsed) {
            useSandpaper();
            return;
        }

        if (state == State.PLAYING) {
            if (e.getButton() == MouseEvent.BUTTON1) {
                if (e.getY() >= tableY - 200) {
                    requestPlayerShot();
                }
            }
        }
    }

    @Override
    public void mousePressed(MouseEvent e) { }

    @Override
    public void mouseReleased(MouseEvent e) { }

    @Override
    public void mouseEntered(MouseEvent e) { }

    @Override
    public void mouseExited(MouseEvent e) { }

    @Override
    public void mouseDragged(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override
    public void keyTyped(KeyEvent e) { }

    @Override
    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();

        if (code == KeyEvent.VK_ENTER) {
            if (state == State.MENU) {
                startFromMenu();
            }
        }

        if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_SPACE) {
            if (state == State.PLAYING || state == State.PAUSED) {
                togglePause();
            }
        }

        if (code == KeyEvent.VK_R) {
            if (isEnd()) {
                resetGame();
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) { }
}

// ============================================================
//                       AMMO SLOT
// ============================================================

class AmmoSlot {
    boolean live;
    boolean used;

    AmmoSlot(boolean live) {
        this.live = live;
        this.used = false;
    }
}

// ============================================================
//                       FLOATING TEXT
// ============================================================

class FloatingText {

    String text;
    double x, y;
    Color color;
    double vy = -0.5;
    double life = 1.0;
    boolean alive = true;

    Font font = new Font("Consolas", Font.BOLD, 22);

    FloatingText(String text, double x, double y, Color color) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.color = color;
    }

    void update() {
        if (!alive) return;
        y += vy * 16;
        life -= 0.016;
        if (life <= 0) alive = false;
    }

    void draw(Graphics2D g) {
        if (!alive) return;
        float a = (float) Math.max(0, life);
        Color c = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (a * 255));
        g.setFont(font);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        g.drawString(text, (int) (x - tw / 2), (int) y);
    }
}

// ============================================================
//                       BUTTON
// ============================================================

class RectButton {

    String text;
    int x, y, w, h;
    boolean hover = false;

    Font font = new Font("Consolas", Font.BOLD, 22);

    RectButton(String text, int x, int y, int w, int h) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    void update(int mx, int my) {
        hover = contains(mx, my);
    }

    boolean contains(int mx, int my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    void draw(Graphics2D g) {
        if (hover) {
            g.setColor(new Color(255, 255, 255, 40));
            g.fillRoundRect(x - 4, y - 4, w + 8, h + 8, 24, 24);
        }

        g.setColor(new Color(20, 20, 20, 230));
        g.fillRoundRect(x, y, w, h, 24, 24);

        g.setColor(new Color(200, 200, 200));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(x, y, w, h, 24, 24);

        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        int th = fm.getAscent();
        g.drawString(text, x + w / 2 - tw / 2, y + h / 2 + th / 2 - 4);
    }
}

// ============================================================
//                       ITEM ICON
// ============================================================

class ItemIcon {

    enum Type { CIGAR, BEER, SANDPAPER }

    Type type;
    int x, y, w, h;
    boolean hover = false;
    boolean enabled = true;

    Font font = new Font("Consolas", Font.PLAIN, 12);

    ItemIcon(Type type, int x, int y, int w, int h) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    void update(int mx, int my, boolean enabled) {
        this.enabled = enabled;
        hover = contains(mx, my) && enabled;
    }

    boolean contains(int mx, int my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    void draw(Graphics2D g, boolean enabled) {
        this.enabled = enabled;

        if (!enabled) {
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRoundRect(x, y, w, h, 10, 10);
        }

        if (hover) {
            g.setColor(new Color(255, 255, 255, 40));
            g.fillRoundRect(x - 3, y - 3, w + 6, h + 6, 12, 12);
        }

        g.setColor(new Color(20, 20, 20, 230));
        g.fillRoundRect(x, y, w, h, 10, 10);

        g.setColor(new Color(200, 200, 200));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(x, y, w, h, 10, 10);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(x, y);

        switch (type) {
            case CIGAR -> {
                g2.setColor(new Color(230, 230, 230));
                g2.fillRoundRect(8, h / 2 - 6, w - 20, 12, 6, 6);
                g2.setColor(new Color(200, 60, 40));
                g2.fillRoundRect(w - 18, h / 2 - 6, 10, 12, 6, 6);
            }
            case BEER -> {
                g2.setColor(new Color(220, 190, 60));
                g2.fillRoundRect(w / 2 - 12, 6, 24, h - 12, 8, 8);
                g2.setColor(new Color(240, 240, 240));
                g2.fillRect(w / 2 - 12, 10, 24, 8);
            }
            case SANDPAPER -> {
                g2.setColor(new Color(230, 230, 230));
                g2.fillRoundRect(8, 8, w - 16, h - 16, 6, 6);
                g2.setColor(new Color(180, 180, 180));
                for (int i = 0; i < 5; i++) {
                    int px = 10 + i * 6;
                    int py = 10 + (i % 2) * 4;
                    g2.fillOval(px, py, 3, 3);
                }
            }
        }

        g2.dispose();

        g.setFont(font);
        g.setColor(new Color(200, 200, 200));
        String label = switch (type) {
            case CIGAR -> "CIGAR";
            case BEER -> "BEER";
            case SANDPAPER -> "SAND";
        };
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label);
        g.drawString(label, x + w / 2 - tw / 2, y + h + fm.getAscent() + 2);
    }
}