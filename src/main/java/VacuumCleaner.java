import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Simple reflex vacuum cleaner agent in a two-location world (A, B).
 * Locations get dirty with a probability that grows with the time since they were last cleaned.
 * Run with: jade.Boot -agents vacuum:VacuumCleaner
 */
public class VacuumCleaner extends Agent {

    enum Position { A, B }
    enum Dirt { DIRTY, CLEAN }
    enum Action { IDLE, GO_LEFT, GO_RIGHT, ASPIRE }

    private static final int TICK_MS = 1000;
    private static final double BASE_DIRT_PROBABILITY = 0.02;
    private static final double DIRT_PROBABILITY_GROWTH = 0.03; // added per tick spent clean
    private static final double MAX_DIRT_PROBABILITY = 0.9;

    // Sensors
    private Position positionSensor;
    private Dirt dirtSensor;

    // Environment state
    private final Map<Position, Dirt> world = new EnumMap<>(Position.class);
    private final Map<Position, Integer> cleanTicks = new EnumMap<>(Position.class);
    private final Random random = new Random();

    private Action lastAction = Action.IDLE;
    private VacuumGui gui;

    @Override
    protected void setup() {
        for (Position p : Position.values()) {
            world.put(p, random.nextBoolean() ? Dirt.DIRTY : Dirt.CLEAN);
            cleanTicks.put(p, 0);
        }
        positionSensor = random.nextBoolean() ? Position.A : Position.B;
        sense();

        SwingUtilities.invokeLater(() -> gui = new VacuumGui(getLocalName()));
        publish();

        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() {
                sense();
                lastAction = decide();
                act(lastAction);
                makeDirtier();
                publish();
                System.out.println(getLocalName() + " -> " + lastAction
                        + " | at " + positionSensor + ", world=" + world);
            }
        });
    }

    @Override
    protected void takeDown() {
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.dispose();
            }
        });
    }

    private void sense() {
        dirtSensor = world.get(positionSensor);
    }

    private Action decide() {
        if (dirtSensor == Dirt.DIRTY) {
            return Action.ASPIRE;
        }
        // current location is clean: stop if the other one is clean too
        if (world.get(other(positionSensor)) == Dirt.CLEAN) {
            return Action.IDLE;
        }
        return positionSensor == Position.A ? Action.GO_RIGHT : Action.GO_LEFT;
    }

    private void act(Action action) {
        switch (action) {
            case ASPIRE:
                world.put(positionSensor, Dirt.CLEAN);
                cleanTicks.put(positionSensor, 0);
                break;
            case GO_LEFT:
                positionSensor = Position.A;
                break;
            case GO_RIGHT:
                positionSensor = Position.B;
                break;
            case IDLE:
            default:
                break;
        }
    }

    /** Each clean location may become dirty; the longer it stayed clean, the likelier. */
    private void makeDirtier() {
        for (Position p : Position.values()) {
            if (world.get(p) == Dirt.CLEAN) {
                cleanTicks.merge(p, 1, Integer::sum);
                if (random.nextDouble() < dirtProbability(p)) {
                    world.put(p, Dirt.DIRTY);
                }
            }
        }
    }

    private double dirtProbability(Position p) {
        if (world.get(p) == Dirt.DIRTY) {
            return 1.0;
        }
        return Math.min(MAX_DIRT_PROBABILITY,
                BASE_DIRT_PROBABILITY + DIRT_PROBABILITY_GROWTH * cleanTicks.get(p));
    }

    private void publish() {
        final Position pos = positionSensor;
        final Action action = lastAction;
        final Dirt a = world.get(Position.A);
        final Dirt b = world.get(Position.B);
        final double pa = dirtProbability(Position.A);
        final double pb = dirtProbability(Position.B);
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.update(pos, action, a, b, pa, pb);
            }
        });
    }

    private static Position other(Position p) {
        return p == Position.A ? Position.B : Position.A;
    }

    /** Swing view: two rooms, the vacuum (animated while moving) and the dirt state. */
    private static class VacuumGui extends JFrame {
        private static final int ROOM_W = 260, ROOM_H = 220, MARGIN = 30;

        private final WorldPanel panel = new WorldPanel();
        private final JLabel status = new JLabel(" ", JLabel.CENTER);

        private double vacuumX = Double.NaN;   // current drawn x of the vacuum
        private double targetX;
        private Action action = Action.IDLE;
        private Dirt dirtA = Dirt.CLEAN, dirtB = Dirt.CLEAN;
        private double probA, probB;
        private int aspireFrames;
        private final javax.swing.Timer animator;

        VacuumGui(String name) {
            super("Vacuum cleaner - " + name);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            status.setFont(status.getFont().deriveFont(Font.BOLD, 16f));
            add(panel, BorderLayout.CENTER);
            add(status, BorderLayout.SOUTH);
            pack();
            setLocationByPlatform(true);
            animator = new javax.swing.Timer(30, e -> step());
            animator.start();
            setVisible(true);
        }

        void update(Position pos, Action act, Dirt a, Dirt b, double pa, double pb) {
            targetX = roomX(pos) + (ROOM_W - 80) / 2.0;
            if (Double.isNaN(vacuumX)) {
                vacuumX = targetX;
            }
            if (act == Action.ASPIRE) {
                aspireFrames = 25;
            }
            action = act;
            dirtA = a;
            dirtB = b;
            probA = pa;
            probB = pb;
            status.setText("Action: " + act + "   |   P(dirty) A: " + percent(pa) + "  B: " + percent(pb));
            panel.repaint();
        }

        private static String percent(double p) {
            return Math.round(p * 100) + "%";
        }

        private void step() {
            boolean changed = false;
            if (!Double.isNaN(vacuumX) && Math.abs(targetX - vacuumX) > 0.5) {
                vacuumX += (targetX - vacuumX) / 4.0;
                changed = true;
            }
            if (aspireFrames > 0) {
                aspireFrames--;
                changed = true;
            }
            if (changed) {
                panel.repaint();
            }
        }

        private static int roomX(Position p) {
            return MARGIN + (p == Position.A ? 0 : ROOM_W + MARGIN);
        }

        private class WorldPanel extends JPanel {
            WorldPanel() {
                setPreferredSize(new Dimension(3 * MARGIN + 2 * ROOM_W, ROOM_H + 2 * MARGIN + 20));
                setBackground(Color.WHITE);
            }

            @Override
            protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawRoom(g, Position.A, dirtA, probA);
                drawRoom(g, Position.B, dirtB, probB);
                if (!Double.isNaN(vacuumX)) {
                    drawVacuum(g);
                }
            }

            private void drawRoom(Graphics2D g, Position p, Dirt dirt, double prob) {
                int x = roomX(p), y = MARGIN;
                g.setColor(dirt == Dirt.DIRTY ? new Color(240, 225, 200) : new Color(225, 245, 255));
                g.fillRect(x, y, ROOM_W, ROOM_H);
                g.setColor(Color.DARK_GRAY);
                g.setStroke(new BasicStroke(3));
                g.drawRect(x, y, ROOM_W, ROOM_H);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
                g.drawString("Location " + p + " - " + dirt, x + 10, y + 24);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.drawString("P(dirty next tick): " + percent(prob), x + 10, y + ROOM_H - 10);
                if (dirt == Dirt.DIRTY) {
                    g.setColor(new Color(110, 70, 30));
                    int[][] spots = {{40, 150}, {100, 170}, {170, 140}, {200, 175}, {70, 110}, {150, 185}, {215, 120}};
                    for (int[] s : spots) {
                        g.fillOval(x + s[0], y + s[1] - 20, 16, 12);
                    }
                }
            }

            private void drawVacuum(Graphics2D g) {
                int x = (int) Math.round(vacuumX), y = MARGIN + ROOM_H - 100;
                boolean aspiring = aspireFrames > 0;
                g.setColor(aspiring ? new Color(255, 140, 0) : new Color(60, 120, 220));
                g.fillRoundRect(x, y, 80, 50, 20, 20);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(2));
                g.drawRoundRect(x, y, 80, 50, 20, 20);
                g.fillOval(x + 8, y + 42, 18, 18);
                g.fillOval(x + 54, y + 42, 18, 18);
                g.setColor(Color.WHITE);
                g.fillOval(x + 28, y + 10, 24, 24);
                g.setColor(Color.BLACK);
                g.fillOval(x + 36, y + 18, 8, 8);
                if (aspiring) {
                    // suction streaks toward the floor
                    g.setColor(new Color(255, 140, 0, 160));
                    for (int i = 0; i < 4; i++) {
                        int off = (aspireFrames * 3 + i * 12) % 40;
                        g.drawLine(x + 15 + i * 17, y + 60 + off / 2, x + 15 + i * 17, y + 70 + off / 2);
                    }
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                    g.drawString("ZZZZ", x + 22, y - 6);
                } else if (action == Action.IDLE) {
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                    g.drawString("idle", x + 28, y - 6);
                }
            }
        }
    }
}
