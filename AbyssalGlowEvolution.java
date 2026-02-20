/*
 AbyssalGlowEvolution.java
 ------------------------------------------
 Swing version of the Abyssal Glow shooter.
 - Restore/Maximize enabled
 - Difficulty increases each 1000 points
 - Uses Stack pools for Bullets & Particles
 ------------------------------------------
 Controls:
   • W A S D / Arrow keys → move
   • Mouse aim + Left Click or Space → shoot
   • ENTER → restart after death
*/

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.font.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;

public class AbyssalGlowEvolution extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new AbyssalGlowEvolution().setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    static final int W = 1000, H = 640;
    private final GamePanel gamePanel;

    public AbyssalGlowEvolution() {
        setTitle("Abyssal Glow — Evolution (Dynamic Difficulty)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        gamePanel = new GamePanel(W, H);
        add(gamePanel, BorderLayout.CENTER);

        pack();
        setResizable(true); // enable restore/maximize
        setMinimumSize(new Dimension(W, H));
        setLocationRelativeTo(null);

        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) {
                gamePanel.requestFocusInWindow();
            }
        });
    }

    // ------------------------------------------------------------
    static class GamePanel extends JPanel implements Runnable,
            MouseListener, MouseMotionListener, KeyListener {

        private final int canvasW, canvasH;
        private final Player player = new Player(W/2.0, H - 120.0, 22.0);

        private final List<Enemy> enemies = new ArrayList<>();
        private final List<Bullet> activeBullets = new ArrayList<>();
        private final List<Particle> activeParticles = new ArrayList<>();

        private final Stack<Bullet> bulletPool = new Stack<>();
        private final Stack<Particle> particlePool = new Stack<>();
        private final Stack<EnemyDescriptor> spawnHistory = new Stack<>();

        private double score = 0;
        private boolean running = true;
        private int hpDisplay = 5;
        private int difficultyLevel = 0;

        private long lastSpawnMs = 0;
        private double spawnIntervalMs = 1100.0;

        private final Set<String> keys = new HashSet<>();
        private volatile boolean mouseDown = false;
        private volatile double mouseX = W/2.0, mouseY = H/2.0;

        private BufferedImage backBuffer;
        private Graphics2D g2;
        private Thread loopThread;

        GamePanel(int w, int h) {
            this.canvasW = w; this.canvasH = h;
            setPreferredSize(new Dimension(w, h));
            setFocusable(true);
            addMouseListener(this);
            addMouseMotionListener(this);
            addKeyListener(this);

            // Key binding for restart (works even if focus shifts)
            InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = getActionMap();
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "restart");
            am.put("restart", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    if (!running) restart();
                }
            });

            backBuffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            g2 = backBuffer.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // initial seed
            for (int i = 0; i < 3; i++) spawnEnemy();

            loopThread = new Thread(this, "GameLoop");
            loopThread.setDaemon(true);
            loopThread.start();
        }

        // ---------------- Pools (Stack) ----------------
        private Bullet obtainBullet() {
            return bulletPool.isEmpty() ? new Bullet() : bulletPool.pop();
        }
        private void recycleBullet(Bullet b) { if (bulletPool.size() < 200) bulletPool.push(b); }

        private Particle obtainParticle() {
            return particlePool.isEmpty() ? new Particle() : particlePool.pop();
        }
        private void recycleParticle(Particle p) { if (particlePool.size() < 400) particlePool.push(p); }

        // ---------------- Utility ----------------
        private static double rnd(double a,double b){return a + Math.random()*(b - a);}

        private void spawnEnemy() {
            double x = rnd(60, canvasW - 60);
            double y = -60;
            String type = Math.random() < 0.65 ? "float" : "dart";
            double r = type.equals("float") ? rnd(18,36) : rnd(8,14);
            double hp = type.equals("float") ? rnd(40,90) : rnd(18,36);
            double speed = type.equals("float") ? rnd(12,40) : rnd(60,140);
            Enemy e = new Enemy(x, y, r, hp, speed, type);
            enemies.add(e);
            spawnHistory.push(new EnemyDescriptor(x, y, r, hp, speed, type));
            // limit history size
            if (spawnHistory.size() > 240) spawnHistory.remove(0);
        }

        // ---------------- Main loop ----------------
        @Override
        public void run() {
            long then = System.nanoTime();
            while (true) {
                long now = System.nanoTime();
                double dt = Math.min(0.048, (now - then) / 1_000_000_000.0);
                then = now;

                update(dt, System.currentTimeMillis());
                renderToBuffer(System.currentTimeMillis());
                repaint();

                try { Thread.sleep(8); } catch (InterruptedException ignored) {}
            }
        }

        private void update(double dt, long nowMs) {
            if (!running) return;

            // movement
            double vx = 0, vy = 0;
            if (keys.contains("a") || keys.contains("arrowleft")) vx -= 1;
            if (keys.contains("d") || keys.contains("arrowright")) vx += 1;
            if (keys.contains("w") || keys.contains("arrowup")) vy -= 1;
            if (keys.contains("s") || keys.contains("arrowdown")) vy += 1;
            if (vx != 0 || vy != 0) {
                double len = Math.hypot(vx, vy); if (len == 0) len = 1;
                player.x += (vx/len) * player.speed * dt;
                player.y += (vy/len) * player.speed * dt;
            }

            // clamp player within bounds
            player.x = clamp(player.x, player.r + 6, canvasW - player.r - 6);
            player.y = clamp(player.y, player.r + 6, canvasH - player.r - 6);

            // shooting
            if ((mouseDown || keys.contains(" ")) && running) shoot(nowMs);

            // bullets update (reverse iterate for safe removal)
            for (int i = activeBullets.size()-1; i >= 0; i--) {
                Bullet b = activeBullets.get(i);
                b.update(dt);
                if (b.life <= 0 || b.x < -80 || b.x > canvasW + 80 || b.y < -80 || b.y > canvasH + 80) {
                    activeBullets.remove(i);
                    recycleBullet(b);
                }
            }

            // spawn timing
            lastSpawnMs += dt * 1000.0;
            if (lastSpawnMs >= spawnIntervalMs) {
                spawnEnemy();
                lastSpawnMs = 0;
                if (spawnIntervalMs > 380 && Math.random() < 0.18) spawnIntervalMs -= rnd(10,40);
            }

            // enemies update & collisions
            for (int ei = enemies.size() - 1; ei >= 0; ei--) {
                Enemy e = enemies.get(ei);
                double dx = player.x - e.x, dy = player.y - e.y;
                double dist = Math.hypot(dx, dy); if (dist == 0) dist = 1;
                if (e.type.equals("float")) {
                    double wob = Math.sin(System.currentTimeMillis()/800.0 + e.wobble) * 0.6;
                    e.x += ((dx/dist)*(e.speed*0.5) + wob) * dt;
                    e.y += ((dy/dist)*(e.speed*0.45)) * dt;
                } else {
                    e.x += (dx/dist) * e.speed * dt;
                    e.y += (dy/dist) * e.speed * dt;
                }

                if (!e.entered && e.y > 0 && e.x > -200 && e.x < canvasW + 200) e.entered = true;

                double dToPlayer = Math.hypot(e.x - player.x, e.y - player.y);
                if (dToPlayer < e.r + player.r) {
                    player.hp -= 1;
                    for (int p = 0; p < 10; p++) spawnParticle(e.x, e.y, "rgba(200,255,255,0.12)", 600, 80);
                    enemies.remove(ei);
                    if (player.hp <= 0) { die(); return; }
                    continue;
                }

                // bullet collisions
                boolean hit = false;
                for (int bi = activeBullets.size() - 1; bi >= 0; bi--) {
                    Bullet b = activeBullets.get(bi);
                    double d = Math.hypot(b.x - e.x, b.y - e.y);
                    if (d < b.r + e.r) {
                        e.hp -= 30 + Math.random() * 30;
                        activeBullets.remove(bi);
                        recycleBullet(b);
                        for (int p = 0; p < 6; p++) spawnParticle(b.x, b.y, "rgba(150,255,240,0.4)", 420, 40);
                        if (e.hp <= 0) {
                            for (int p = 0; p < 20; p++) spawnParticle(e.x, e.y, "rgba(120,255,220,0.18)", 900, 120);
                            score += Math.round(6 + e.r*0.8 + (e.type.equals("dart") ? 8 : 0));
                            enemies.remove(ei);
                        }
                        hit = true;
                        break;
                    }
                }
                if (hit) continue;
            }

            // particles update (reverse iterate)
            for (int i = activeParticles.size() - 1; i >= 0; i--) {
                Particle p = activeParticles.get(i);
                p.t += dt * 1000.0;
                p.x += p.vx * dt;
                p.y += p.vy * dt + 6 * dt * (Math.random() - 0.5);
                if (p.t >= p.life) {
                    activeParticles.remove(i);
                    recycleParticle(p);
                }
            }

            // score & difficulty
            score += dt * 1.4;
            hpDisplay = player.hp;
            checkDifficultyIncrease();
        }

        // ---------------- Difficulty escalation ----------------
        private void checkDifficultyIncrease() {
            int newLevel = (int)(score / 1000);
            if (newLevel > difficultyLevel) {
                difficultyLevel = newLevel;
                spawnIntervalMs = Math.max(250, spawnIntervalMs - 80); // faster spawns
                // immediate extra enemies to heighten pace
                for (int i = 0; i < 2 + difficultyLevel; i++) spawnEnemy();
                System.out.println("⚡ Difficulty up! Level " + difficultyLevel +
                        " | New spawn interval: " + spawnIntervalMs + " ms");
            }
        }

        // ---------------- Combat / FX ----------------
        private void shoot(long nowMs) {
            if (nowMs - player.lastShotMs < player.fireCooldownMs) return;
            player.lastShotMs = nowMs;
            int count = Math.random() < 0.18 ? 2 : 1;
            for (int i = 0; i < count; i++) {
                double spread = rnd(-0.12, 0.12);
                double angle = Math.atan2(mouseY - player.y, mouseX - player.x) + spread;
                double speed = rnd(520, 760);
                Bullet b = obtainBullet();
                b.x = player.x + Math.cos(angle) * player.r * 0.6;
                b.y = player.y + Math.sin(angle) * player.r * 0.6;
                b.vx = Math.cos(angle) * speed;
                b.vy = Math.sin(angle) * speed;
                b.r = 6; b.life = 1800;
                activeBullets.add(b);
            }
        }

        private void spawnParticle(double x, double y, String colorHint, double life, double s) {
            Particle p = obtainParticle();
            p.x = x; p.y = y;
            p.vx = rnd(-s, s); p.vy = rnd(-s/1.6, s/1.6);
            p.c = colorHint; p.life = life; p.t = 0; p.r = rnd(1.6, 4.4);
            activeParticles.add(p);
        }

        private void die() {
            running = false;
        }

        private void restart() {
            activeBullets.forEach(this::recycleBullet);
            activeParticles.forEach(this::recycleParticle);
            activeBullets.clear(); activeParticles.clear(); enemies.clear();
            // repopulate initial seed
            for (int i = 0; i < 3; i++) spawnEnemy();
            score = 0; spawnIntervalMs = 1100.0; lastSpawnMs = 0; difficultyLevel = 0;
            player.x = canvasW / 2.0; player.y = canvasH - 120.0; player.hp = 5; player.lastShotMs = 0;
            running = true;
            requestFocusInWindow();
        }

        // ---------------- Rendering ----------------
        private void renderToBuffer(long nowMs) {
            Graphics2D g = g2;
            // clear / base gradient
            g.setComposite(AlphaComposite.Src);
            g.setPaint(new Color(0x001322));
            g.fillRect(0, 0, canvasW, canvasH);
            GradientPaint gp = new GradientPaint(0, 0, new Color(0x001322), 0, (float)(canvasH*0.45), new Color(0x001c2a));
            g.setPaint(gp); g.fillRect(0,0,canvasW,canvasH);

            // plankton layer
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.06f));
            g.setPaint(new Color(0x7fffd4));
            for (int i = 0; i < 30; i++) {
                double x = ((i*47 + nowMs*0.02) % (canvasW+200)) - 100;
                double y = (i*23 + Math.sin(nowMs*0.0006*i)*40) % canvasH;
                g.fill(new Ellipse2D.Double(x, y, 5.6, 3.2));
            }
            g.setComposite(AlphaComposite.SrcOver);

            // midground silhouettes
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
            g.setPaint(new Color(0x001a23));
            for (int i = 0; i < 10; i++) {
                double x = (i*130 + (nowMs*0.01*(i%3+1))) % (canvasW+200) - 60;
                double base = canvasH - 80 - (i%3)*18;
                Path2D p = new Path2D.Double();
                p.moveTo(x, canvasH);
                p.curveTo(x-8, base-80, x+50, base-110, x+32, canvasH-18);
                p.lineTo(x+60, canvasH);
                p.closePath();
                g.fill(p);
            }
            g.setComposite(AlphaComposite.SrcOver);

            // fog overlay
            GradientPaint fog = new GradientPaint(0,0,new Color(0,12,18,0),0,canvasH,new Color(0,0,0,96));
            g.setPaint(fog); g.fillRect(0,0,canvasW,canvasH);

            // particles (behind)
            for (Particle p : activeParticles) {
                float alpha = (float)Math.max(0, 1 - p.t / p.life);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                Color col = parseRGBA(p.c, alpha);
                g.setPaint(col);
                double rad = p.r * (1 - p.t / p.life);
                g.fill(new Ellipse2D.Double(p.x - rad, p.y - rad, rad*2, rad*2));
            }
            g.setComposite(AlphaComposite.SrcOver);

            // enemies (below player)
            for (Enemy e : enemies) {
                float[] dist = {0f, 0.6f, 1f};
                Color[] colors = e.type.equals("float") ?
                        new Color[]{ new Color(150,255,240,46), new Color(80,200,190,15), new Color(0,10,12,0) } :
                        new Color[]{ new Color(255,190,170,40), new Color(220,120,110,12), new Color(0,8,10,0) };
                RadialGradientPaint rgp = new RadialGradientPaint(new Point2D.Double(e.x, e.y), (float)(e.r*2.6), dist, colors);
                g.setPaint(rgp);
                g.fill(new Ellipse2D.Double(e.x - e.r*2.6, e.y - e.r*2.6, e.r*5.2, e.r*5.2));

                g.setPaint(e.type.equals("float") ? new Color(0x9fffea) : new Color(0xffd9c8));
                g.fill(new Ellipse2D.Double(e.x - e.r, e.y - e.r, e.r*2, e.r*2));

                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g.setPaint(new Color(255,255,255,15));
                double rx = e.r * 0.7;
                g.fill(new Ellipse2D.Double(e.x - e.r*0.25 - rx, e.y - e.r*0.25 - rx, rx*2, rx*2));
                g.setComposite(AlphaComposite.SrcOver);

                g.setPaint(e.type.equals("float") ? new Color(0x004b3f) : new Color(0x5a1608));
                double rr = Math.max(2, e.r*0.22);
                g.fill(new Ellipse2D.Double(e.x - rr, e.y - rr, rr*2, rr*2));
            }

            // bullets
            for (Bullet b : activeBullets) {
                float lifePct = (float)Math.max(0, Math.min(1, b.life / 1800.0));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f * lifePct));
                g.setPaint(new Color(0xcfeffb));
                g.fill(new Ellipse2D.Double(b.x - b.r, b.y - b.r, b.r*2, b.r*2));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f * lifePct));
                g.fill(new Ellipse2D.Double(b.x - b.r*2.6, b.y - b.r*2.6, b.r*5.2, b.r*5.2));
            }
            g.setComposite(AlphaComposite.SrcOver);

            // player (on top) rotated toward mouse
            double ang = Math.atan2(mouseY - player.y, mouseX - player.x);
            AffineTransform old = g.getTransform();
            g.translate(player.x, player.y);
            g.rotate(ang);

            // glow silhouette
            g.setPaint(new Color(27,248,232));
            Path2D silhouette = new Path2D.Double();
            silhouette.moveTo(player.r*0.9, 0);
            silhouette.quadTo(player.r*-0.8, -player.r*0.9, -player.r*0.65, 0);
            silhouette.quadTo(player.r*-0.8, player.r*0.9, player.r*0.9, 0);
            silhouette.closePath();
            g.fill(silhouette);

            // highlight
            g.setPaint(new Color(0xbffdf8));
            Path2D highlight = new Path2D.Double();
            highlight.moveTo(player.r*0.86, 0);
            highlight.quadTo(-player.r*0.55, -player.r*0.6, -player.r*0.4, 0);
            highlight.quadTo(-player.r*0.55, player.r*0.6, player.r*0.86, 0);
            g.fill(highlight);

            // cockpit glow
            g.setPaint(new Color(0,10,20,32));
            g.fill(new Ellipse2D.Double(-player.r*0.1 - player.r*0.32, -player.r*0.42, player.r*0.64, player.r*0.84));

            g.setTransform(old);

            // top overlay particles (front layer)
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.06f));
            g.setPaint(new Color(0x9ff1e6));
            for (int i = 0; i < 18; i++) {
                double x = ((i*73 + nowMs*0.03) % (canvasW+200)) - 100;
                double y = ((i*37*1.2 + Math.cos(nowMs*0.0009*i)*12) % canvasH);
                g.fill(new Ellipse2D.Double(x, y, 4.4, 2.4));
            }
            g.setComposite(AlphaComposite.SrcOver);

            // vignette
            RadialGradientPaint vig = new RadialGradientPaint(new Point2D.Double(canvasW/2.0, canvasH/2.0),
                    Math.max(canvasW, canvasH),
                    new float[]{0f,1f},
                    new Color[]{ new Color(0,0,0,0), new Color(0,0,0,118) });
            g.setPaint(vig);
            g.fillRect(0,0,canvasW,canvasH);

            // HUD
            g.setComposite(AlphaComposite.SrcOver);
            g.setFont(new Font("Monospaced", Font.PLAIN, 15));
            g.setPaint(new Color(0xdff7f1));
            String scoreStr = "Score: " + (int)Math.floor(score);
            String hpStr = "HP: " + hpDisplay;
            g.drawString(scoreStr, 16, 28);
            g.drawString(hpStr, 200, 28);

            // Game over overlay
            if (!running) {
                g.setPaint(new Color(0,0,0,160));
                g.fillRect(canvasW/2 - 240, canvasH/2 - 100, 480, 200);
                g.setPaint(new Color(232,251,255));
                g.setFont(new Font("Serif", Font.BOLD, 36));
                drawCenteredString(g, "YOU FELL TO THE ABYSS", new Rectangle(canvasW/2 - 240, canvasH/2 - 100, 480, 80), g.getFont());
                g.setFont(new Font("Serif", Font.PLAIN, 18));
                drawCenteredString(g, "Final Score: " + (int)Math.floor(score), new Rectangle(canvasW/2 - 240, canvasH/2 - 20, 480, 40), g.getFont());
                g.setFont(new Font("SansSerif", Font.PLAIN, 14));
                drawCenteredString(g, "Press ENTER to restart", new Rectangle(canvasW/2 - 240, canvasH/2 + 28, 480, 32), g.getFont());
            }
        }

        private void drawCenteredString(Graphics2D g, String text, Rectangle rect, Font font) {
            FontRenderContext frc = g.getFontRenderContext();
            TextLayout layout = new TextLayout(text, font, frc);
            Rectangle2D bounds = layout.getBounds();
            double x = rect.x + (rect.width - bounds.getWidth())/2;
            double y = rect.y + (rect.height - bounds.getHeight())/2 + layout.getAscent();
            g.setPaint(Color.WHITE);
            layout.draw(g, (float)x, (float)y);
        }

        private Color parseRGBA(String s, float alphaOverride) {
            try {
                if (s != null && s.startsWith("rgba(")) {
                    String inner = s.substring(5, s.length() - 1);
                    String[] parts = inner.split(",");
                    int r = Integer.parseInt(parts[0].trim());
                    int g = Integer.parseInt(parts[1].trim());
                    int b = Integer.parseInt(parts[2].trim());
                    float a = Float.parseFloat(parts[3].trim());
                    return new Color(r, g, b, Math.max(0, Math.min(1f, a)));
                }
            } catch (Exception ignored) {}
            return new Color(180,255,240, Math.round(255 * alphaOverride));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(backBuffer, 0, 0, null);
        }

        // ---------------- Input listeners ----------------
        @Override public void mouseClicked(MouseEvent e) { requestFocusInWindow(); }
        @Override public void mousePressed(MouseEvent e) { if (e.getButton() == MouseEvent.BUTTON1) mouseDown = true; requestFocusInWindow(); }
        @Override public void mouseReleased(MouseEvent e) { if (e.getButton() == MouseEvent.BUTTON1) mouseDown = false; }
        @Override public void mouseEntered(MouseEvent e) {}
        @Override public void mouseExited(MouseEvent e) { mouseDown = false; }
        @Override public void mouseDragged(MouseEvent e) { updateMouse(e); }
        @Override public void mouseMoved(MouseEvent e) { updateMouse(e); }
        private void updateMouse(MouseEvent e) { Point p = e.getPoint(); mouseX = p.getX(); mouseY = p.getY(); }

        @Override public void keyTyped(KeyEvent e) {}
        @Override public void keyPressed(KeyEvent e) {
            String k = KeyEvent.getKeyText(e.getKeyCode()).toLowerCase();
            if (e.getKeyCode() == KeyEvent.VK_SPACE) k = " ";
            if (e.getKeyCode() == KeyEvent.VK_LEFT) k = "arrowleft";
            if (e.getKeyCode() == KeyEvent.VK_RIGHT) k = "arrowright";
            if (e.getKeyCode() == KeyEvent.VK_UP) k = "arrowup";
            if (e.getKeyCode() == KeyEvent.VK_DOWN) k = "arrowdown";
            keys.add(k);
            if (!running && e.getKeyCode() == KeyEvent.VK_ENTER) restart();
        }
        @Override public void keyReleased(KeyEvent e) {
            String k = KeyEvent.getKeyText(e.getKeyCode()).toLowerCase();
            if (e.getKeyCode() == KeyEvent.VK_SPACE) k = " ";
            if (e.getKeyCode() == KeyEvent.VK_LEFT) k = "arrowleft";
            if (e.getKeyCode() == KeyEvent.VK_RIGHT) k = "arrowright";
            if (e.getKeyCode() == KeyEvent.VK_UP) k = "arrowup";
            if (e.getKeyCode() == KeyEvent.VK_DOWN) k = "arrowdown";
            keys.remove(k);
        }

        // ---------------- Small helpers & classes ----------------
        private static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }

        static class Player {
            double x,y,r;
            double speed = 260;
            int hp = 5;
            long lastShotMs = 0;
            int fireCooldownMs = 120;
            Player(double x,double y,double r) { this.x = x; this.y = y; this.r = r; }
        }

        static class Enemy {
            double x,y,r;
            double hp;
            double speed;
            String type;
            double wobble = Math.random()*6;
            boolean entered = false;
            Enemy(double x,double y,double r,double hp,double speed,String type) {
                this.x = x; this.y = y; this.r = r; this.hp = hp; this.speed = speed; this.type = type;
            }
        }

        static class Bullet {
            double x,y,vx,vy;
            double r;
            double life;
            void reset() { x = y = vx = vy = 0; r = 6; life = 0; }
            void update(double dt) { x += vx * dt; y += vy * dt; life -= dt * 1000.0; }
        }

        static class Particle {
            double x,y,vx,vy;
            String c;
            double life;
            double t;
            double r;
            void reset() { x = y = vx = vy = 0; c = "rgba(255,255,255,1)"; life = 0; t = 0; r = 1; }
        }

        static class EnemyDescriptor {
            double x,y,r,hp,speed; String type;
            EnemyDescriptor(double x,double y,double r,double hp,double speed,String type) {
                this.x=x; this.y=y; this.r=r; this.hp=hp; this.speed=speed; this.type=type;
            }
        }
    }
}
