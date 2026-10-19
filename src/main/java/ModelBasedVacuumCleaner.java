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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Model-based reflex vacuum cleaner in a two-location world (A, B).
 *
 * Unlike the plain reflex agent, this one can only perceive the location it is standing on
 * (partial observability). To act sensibly it keeps:
 *  - a bounded HISTORY of its percepts,
 *  - a BELIEF about each location, rebuilt from that history, that predicts when the location will be dirty.
 *
 * Run with: jade.Boot -agents vacuum:ModelBasedVacuumCleaner
 * Optional argument: maximum history length (default 30), e.g. -agents "vacuum:ModelBasedVacuumCleaner(50)"
 */
public class ModelBasedVacuumCleaner extends Agent {

    enum Position { A, B }
    enum Dirt { DIRTY, CLEAN }
    enum Action { IDLE, GO_LEFT, GO_RIGHT, ASPIRE }

    private static final int TICK_MS = 1000;
    private static final int DEFAULT_MAX_HISTORY = 30;
    private static final double PRIOR_TIME_TO_DIRTY = 5.0; // used until the agent has observed any dirt appearing
    private static final int TRAVEL_TICKS = 1;              // moving to the other location takes one tick

    // Environment dynamics (the agent does not know them, it has to learn them): the longer a location
    // stays clean, the more likely it becomes dirty.
    private static final double BASE_DIRT_PROBABILITY = 0.02;
    private static final double DIRT_PROBABILITY_GROWTH = 0.03;
    private static final double MAX_DIRT_PROBABILITY = 0.9;

    /** What the agent perceives at the start of a tick: where it is and whether that spot is dirty. */
    private static final class Percept {
        final int tick;
        final Position position;
        final Dirt dirt;

        Percept(int tick, Position position, Dirt dirt) {
            this.tick = tick;
            this.position = position;
            this.dirt = dirt;
        }
    }

    /** The agent's belief about one location, derived from the percept history. */
    static final class Belief {
        final Integer lastCleanTick;    // last time the location was known to be clean (null = never seen)
        final double meanTimeToDirty;   // average number of ticks a location stays clean
        final int samples;              // how many "clean -> dirty" observations the average is based on
        final double predictedDirtyTick; // tick at which the location is expected to be dirty
        final double pDirty;            // believed probability that the location is dirty right now

        Belief(Integer lastCleanTick, double meanTimeToDirty, int samples, double predictedDirtyTick, double pDirty) {
            this.lastCleanTick = lastCleanTick;
            this.meanTimeToDirty = meanTimeToDirty;
            this.samples = samples;
            this.predictedDirtyTick = predictedDirtyTick;
            this.pDirty = pDirty;
        }
    }

    // Sensors
    private Position positionSensor;
    private Dirt dirtSensor;

    // Environment state (hidden from the agent except for its own location)
    private final Map<Position, Dirt> world = new EnumMap<>(Position.class);
    private final Map<Position, Integer> cleanTicks = new EnumMap<>(Position.class);
    private final Random random = new Random();

    // Agent's internal state
    private int maxHistory = DEFAULT_MAX_HISTORY;
    private final Deque<Percept> history = new ArrayDeque<>();
    private final Map<Position, Belief> beliefs = new EnumMap<>(Position.class);
    private int tick = 0;
    private Action lastAction = Action.IDLE;
    private ModelGui gui;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            try {
                maxHistory = Math.max(1, Integer.parseInt(args[0].toString()));
            } catch (NumberFormatException e) {
                System.err.println("Invalid history length '" + args[0] + "', using " + DEFAULT_MAX_HISTORY);
            }
        }
        for (Position p : Position.values()) {
            world.put(p, random.nextBoolean() ? Dirt.DIRTY : Dirt.CLEAN);
            cleanTicks.put(p, 0);
        }
        positionSensor = random.nextBoolean() ? Position.A : Position.B;

        SwingUtilities.invokeLater(() -> gui = new ModelGui(getLocalName()));

        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() {
                sense();
                updateBeliefs();
                lastAction = decide();
                act(lastAction);
                makeDirtier();
                publish();
                System.out.println(getLocalName() + " tick " + tick + " -> " + lastAction
                        + " | at " + positionSensor + ", world=" + world);
                tick++;
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

    /** Perception: read the sensors and append the percept to the bounded history. */
    private void sense() {
        dirtSensor = world.get(positionSensor);
        history.addLast(new Percept(tick, positionSensor, dirtSensor));
        while (history.size() > maxHistory) {
            history.removeFirst(); // forget the oldest percepts: the model only reflects recent behaviour
        }
    }

    /**
     * BELIEF FORMATION AND UPDATE.
     * The beliefs are not patched incrementally: they are recomputed every tick from the bounded history,
     * so old percepts that fall out of the window automatically stop influencing them.
     * For each location L we scan, in time order, the percepts taken while standing on L:
     *  1. A CLEAN percept at tick t tells us L was clean at t.
     *  2. A DIRTY percept at tick t means dirt appeared after the last time we knew L was clean (tick c).
     *     That yields one sample of the "time to become dirty": t - c (at least 1 tick).
     *     Since this agent always aspires when it finds dirt, L is clean again from t on, so c = t.
     *  3. meanTimeToDirty = average of the samples (a prior is used while there are none).
     *     Only completed "clean -> dirty" intervals are counted, so the estimate is slightly
     *     optimistic about how fast dirt appears; this is a simple, deliberately cautious choice.
     * From the last known-clean tick c and the mean m we derive the prediction for L:
     *     predictedDirtyTick = c + m           pDirty(now) = min(1, (now - c) / m)
     * A location never seen has no c: it is treated as "possibly dirty already" (pDirty = 0.5, predicted = now).
     * The location we stand on is observed directly, so there the belief is certain (0 or 1).
     */
    private void updateBeliefs() {
        for (Position p : Position.values()) {
            Integer lastClean = null;
            int samples = 0;
            double sum = 0;
            for (Percept e : history) {
                if (e.position != p) {
                    continue;
                }
                if (e.dirt == Dirt.DIRTY && lastClean != null) {
                    sum += Math.max(1, e.tick - lastClean);
                    samples++;
                }
                lastClean = e.tick; // clean now, or about to be aspired right away
            }
            double mean = samples > 0 ? sum / samples : PRIOR_TIME_TO_DIRTY;
            double predicted;
            double pDirty;
            if (p == positionSensor) {
                predicted = dirtSensor == Dirt.DIRTY ? tick : (lastClean == null ? tick : lastClean + mean);
                pDirty = dirtSensor == Dirt.DIRTY ? 1.0 : 0.0;
            } else if (lastClean == null) {
                predicted = tick;
                pDirty = 0.5;
            } else {
                predicted = lastClean + mean;
                pDirty = Math.min(1.0, (tick - lastClean) / mean);
            }
            beliefs.put(p, new Belief(lastClean, mean, samples, predicted, pDirty));
        }
    }

    /**
     * Decision: a reflex rule over the percept AND the belief.
     *  - dirty here: aspire.
     *  - otherwise move to the other location only if, by the time we arrive, it is predicted to be dirty
     *    (or we know nothing about it); in the meantime stay idle rather than commute uselessly.
     */
    private Action decide() {
        if (dirtSensor == Dirt.DIRTY) {
            return Action.ASPIRE;
        }
        Belief otherBelief = beliefs.get(other(positionSensor));
        if (tick + TRAVEL_TICKS >= otherBelief.predictedDirtyTick) {
            return positionSensor == Position.A ? Action.GO_RIGHT : Action.GO_LEFT;
        }
        return Action.IDLE;
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

    /** Environment: each clean location may become dirty; the longer it stayed clean, the likelier. */
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
        return Math.min(MAX_DIRT_PROBABILITY,
                BASE_DIRT_PROBABILITY + DIRT_PROBABILITY_GROWTH * cleanTicks.get(p));
    }

    private void publish() {
        final Position pos = positionSensor;
        final Action action = lastAction;
        final Dirt a = world.get(Position.A);
        final Dirt b = world.get(Position.B);
        final Belief ba = beliefs.get(Position.A);
        final Belief bb = beliefs.get(Position.B);
        final int t = tick;
        final int size = history.size();
        final int max = maxHistory;
        SwingUtilities.invokeLater(() -> {
            if (gui != null) {
                gui.update(t, pos, action, a, b, ba, bb, size, max);
            }
        });
    }

    private static Position other(Position p) {
        return p == Position.A ? Position.B : Position.A;
    }

    /** Swing view: the two locations (ground truth), the vacuum, and the agent's belief on each location. */
    private static class ModelGui extends JFrame {
        private static final int ROOM_W = 280, ROOM_H = 330, MARGIN = 30;

        private final WorldPanel panel = new WorldPanel();
        private final JLabel status = new JLabel(" ", JLabel.CENTER);

        private double vacuumX = Double.NaN;
        private double targetX;
        private Action action = Action.IDLE;
        private int tick, historySize, maxHistory;
        private Dirt dirtA = Dirt.CLEAN, dirtB = Dirt.CLEAN;
        private Belief beliefA, beliefB;
        private int aspireFrames;

        ModelGui(String name) {
            super("Model-based vacuum cleaner - " + name);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            status.setFont(status.getFont().deriveFont(Font.BOLD, 15f));
            add(panel, BorderLayout.CENTER);
            add(status, BorderLayout.SOUTH);
            pack();
            setLocationByPlatform(true);
            new javax.swing.Timer(30, e -> step()).start();
            setVisible(true);
        }

        void update(int t, Position pos, Action act, Dirt a, Dirt b, Belief ba, Belief bb, int size, int max) {
            targetX = roomX(pos) + (ROOM_W - 80) / 2.0;
            if (Double.isNaN(vacuumX)) {
                vacuumX = targetX;
            }
            if (act == Action.ASPIRE) {
                aspireFrames = 25;
            }
            tick = t;
            action = act;
            dirtA = a;
            dirtB = b;
            beliefA = ba;
            beliefB = bb;
            historySize = size;
            maxHistory = max;
            status.setText("Tick " + t + "  |  Action: " + act + "  |  History: " + size + "/" + max);
            panel.repaint();
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

        private static String percent(double p) {
            return Math.round(p * 100) + "%";
        }

        private class WorldPanel extends JPanel {
            WorldPanel() {
                setPreferredSize(new Dimension(3 * MARGIN + 2 * ROOM_W, ROOM_H + 2 * MARGIN));
                setBackground(Color.WHITE);
            }

            @Override
            protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawRoom(g, Position.A, dirtA, beliefA);
                drawRoom(g, Position.B, dirtB, beliefB);
                if (!Double.isNaN(vacuumX)) {
                    drawVacuum(g);
                }
            }

            private void drawRoom(Graphics2D g, Position p, Dirt dirt, Belief b) {
                int x = roomX(p), y = MARGIN;
                g.setColor(dirt == Dirt.DIRTY ? new Color(240, 225, 200) : new Color(225, 245, 255));
                g.fillRect(x, y, ROOM_W, ROOM_H);
                g.setColor(Color.DARK_GRAY);
                g.setStroke(new BasicStroke(3));
                g.drawRect(x, y, ROOM_W, ROOM_H);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
                g.drawString("Location " + p + " - actually " + dirt, x + 10, y + 24);

                if (dirt == Dirt.DIRTY) {
                    g.setColor(new Color(110, 70, 30));
                    int[][] spots = {{40, 170}, {100, 185}, {170, 160}, {210, 190}, {70, 150}, {150, 195}, {235, 165}};
                    for (int[] s : spots) {
                        g.fillOval(x + s[0], y + s[1], 16, 12);
                    }
                }
                if (b != null) {
                    drawBelief(g, x, y + 215, b);
                }
            }

            /** Belief box: what the agent currently thinks about this location. */
            private void drawBelief(Graphics2D g, int x, int y, Belief b) {
                g.setColor(new Color(255, 255, 255, 220));
                g.fillRoundRect(x + 8, y, ROOM_W - 16, 108, 10, 10);
                g.setColor(new Color(90, 50, 140));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(x + 8, y, ROOM_W - 16, 108, 10, 10);

                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                g.drawString("Agent's belief", x + 16, y + 16);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.drawString("P(dirty now): " + percent(b.pDirty), x + 16, y + 33);

                int barX = x + 120, barY = y + 23, barW = ROOM_W - 150;
                g.setColor(new Color(220, 220, 220));
                g.fillRect(barX, barY, barW, 10);
                g.setColor(new Color(150, 90, 40));
                g.fillRect(barX, barY, (int) Math.round(barW * b.pDirty), 10);
                g.setColor(Color.DARK_GRAY);
                g.drawRect(barX, barY, barW, 10);

                g.setColor(new Color(90, 50, 140));
                g.drawString("Last known clean: "
                        + (b.lastCleanTick == null ? "never seen" : "tick " + b.lastCleanTick), x + 16, y + 52);
                g.drawString(String.format("Avg time to dirty: %.1f ticks (%s)", b.meanTimeToDirty,
                        b.samples == 0 ? "prior" : b.samples + " samples"), x + 16, y + 69);
                g.drawString("Predicted dirty at: tick " + Math.max(tick, (int) Math.ceil(b.predictedDirtyTick))
                        + (b.predictedDirtyTick <= tick ? " (already)" : ""), x + 16, y + 86);
                g.drawString("History window: " + historySize + "/" + maxHistory + " percepts", x + 16, y + 102);
            }

            private void drawVacuum(Graphics2D g) {
                int x = (int) Math.round(vacuumX), y = MARGIN + 110;
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
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                if (aspiring) {
                    g.setColor(new Color(255, 140, 0, 160));
                    for (int i = 0; i < 4; i++) {
                        int off = (aspireFrames * 3 + i * 12) % 40;
                        g.drawLine(x + 15 + i * 17, y + 60 + off / 2, x + 15 + i * 17, y + 70 + off / 2);
                    }
                    g.drawString("ZZZZ", x + 22, y - 6);
                } else if (action == Action.IDLE) {
                    g.drawString("idle", x + 28, y - 6);
                }
            }
        }
    }
}
