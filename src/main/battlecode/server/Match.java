package battlecode.server;

import battlecode.common.GameConstants;
import battlecode.common.Team;
import battlecode.engine.Engine;
import battlecode.engine.GameState;
import battlecode.engine.signal.Signal;
import battlecode.serial.*;
import battlecode.world.GameWorld;

import java.util.Observable;

//import battlecode.tournament.TournamentType;
//import battlecode.tournament.Match.Type;

/**
 * Abstracts the game engine for the server. This class is responsible for
 * starting and managing matches and exporting match status and results to the
 * server.
 */
public class Match extends Observable {

    /**
     * The Engine instance to use to run the game.
     */
    private Engine engine;

    /**
     * The GenericWorld for getting signals.
     */
    private GameWorld gameWorld;

    /**
     * The MatchInfo from which this match was created.
     */
    private final MatchInfo info;

    /**
     * The map for this match (one of the maps in info).
     */
    private final String map;

    /**
     * The command-line/config file options to use.
     */
    private final Config options;

    private long[][] state = new long[2][GameConstants.TEAM_MEMORY_LENGTH];

    private int number;

    private int count;

    private boolean bytecodesUsedEnabled = true;

    private long[][] computedTeamMemory = null;

    /**
     * Creates a new match with the given parameters and options.
     *
     * @param info    the teams and map to use when running this match
     * @param options options relevant to match creation (i.e., default map path)
     */
    public Match(MatchInfo info, String map, Config options, int number,
                 int count) {

        this.info = info;
        this.map = map;
        this.options = options;

        this.number = number;
        this.count = count;

        this.engine = null;
        this.gameWorld = null;
    }

    /**
     * Sets up the engine for this match. Because Engine's constructor
     * manipulates static state, engine object creation should not be done at
     * match creation time!
     */
    public void initialize() {

        this.bytecodesUsedEnabled =
                options.getBoolean("bc.engine.bytecodes-used");

        String mapPath = options.get("bc.game.map-path");

        // Create a new engine.
        this.engine = new Engine(info.getTeamA(), info.getTeamB(), map,
                mapPath, this.state);

        // Get the viewer from the engine.
        this.gameWorld = engine.getGameWorld();
        assert this.gameWorld != null;
    }

    /**
     * Sends a signal directly to the game engine, possibly altering the match
     * state.
     *
     * @param signal the signal to send to the engine
     * @return the signals that represent the effect of the alteration, or an
     *         empty signal array if there was no effect
     */
    public Signal[] alter(Signal signal) {
        if (engine.receiveSignal(signal))
            return gameWorld.getAllSignals(false);
        else
            return new Signal[0];
    }

    /**
     * Determines whether or not this match is ready to run.
     *
     * @return true if the match has been initialized, false otherwise
     */
    public boolean isInitialized() {
        return this.engine != null;
    }

    /**
     * Runs the next round, returning a delta containing all the signals raised
     * during that round. Notifies observers of anything other than a successful
     * delta-producing run.
     *
     * @return the signals generated for the next round of the game, or null if
     *         the engine's result was a breakpoint or completion
     */
    public RoundDelta getRound() {

        // Run the next round.
        GameState result = engine.runRound();

        // Notify the server of any other result.
        if (result == GameState.BREAKPOINT) {
            setChanged();
            notifyObservers(result);
            clearChanged();
        }

        if (result == GameState.DONE)
            return null;

        // Serialize the newly modified GameWorld.
        return new RoundDelta(
                gameWorld.getAllSignals(this.bytecodesUsedEnabled));
    }

    /**
     * Queries the engine for stats for the most recent round and returns them.
     *
     * @return round stats from the engine
     */
    public RoundStats getStats() {
        return gameWorld.getRoundStats();
    }

    /**
     * Queries the engine for stats for the whole match.
     *
     * @return game stats from the engine
     */
    public GameStats getGameStats() {
        return gameWorld.getGameStats();
    }

    /**
     * Gets the header data for this match.
     *
     * @return this match's header
     */
    public MatchHeader getHeader() {
        return new MatchHeader(gameWorld.getGameMap(), state, number,
                count);
    }

    /**
     * Gets team and map metadata for this match.
     *
     * @return an ExtensibleMetadata with teams and maps
     */
    public ExtensibleMetadata getHeaderMetadata() {
        ExtensibleMetadata ex = new ExtensibleMetadata();
        ex.put("type", "header");
        ex.put("team-a", info.getTeamA());
        ex.put("team-b", info.getTeamB());
        ex.put("maps", info.getMaps());
        return ex;
    }

    /**
     * Gets the footer data for this match.
     *
     * @return this match's footer
     */
    public MatchFooter getFooter() {
        return new MatchFooter(gameWorld.getWinner(),
                getComputedTeamMemory());
    }

    /**
     * Gets the winner of this match.
     *
     * @return the Team that has won the match, or null if the match has not yet
     *         finished
     */
    public Team getWinner() {
        if (hasMoreRounds())
            return null;
        return gameWorld.getWinner();
    }

    /**
     * Determines whether or not there are more rounds to be run in this match.
     *
     * @return true if the match has finished running, false otherwise
     */
    public boolean hasMoreRounds() {
        return engine.isRunning();
    }

    /**
     * Produces a string for the winner of the match.
     *
     * @return A string representing the match's winner.
     */
    public String getWinnerString() {

        String teamName;

        switch (getWinner()) {
            case A:
                teamName = info.getTeamA() + " (A)";
                break;

            case B:
                teamName = info.getTeamB() + " (B)";
                break;

            default:
                teamName = "nobody";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < (50 - teamName.length()) / 2; i++)
            sb.append(' ');
        sb.append(teamName);
        sb.append(" wins (round ").append(getRoundNumber()).append(")");

        sb.append("\nReason: ");
        GameStats stats = gameWorld.getGameStats();
        DominationFactor dom = stats.getDominationFactor();
        if (dom == DominationFactor.DESTROYED)
            sb.append("The winning team won by destruction.");
        else if (dom == DominationFactor.PWNED)
            sb.append("The winning team won on tiebreakers (more Archons remaining).");
        else if (dom == DominationFactor.OWNED)
            sb.append("The winning team won on tiebreakers (more Archon health).");
        else if (dom == DominationFactor.BARELY_BEAT)
            sb.append("The winning team won on tiebreakers (more Parts)");
        else if (dom == DominationFactor.WON_BY_DUBIOUS_REASONS)
            sb.append("Team ").append(getWinner()).append(" won arbitrarily.");

        return sb.toString();
    }

    public void setInitialTeamMemory(long[][] state) {
        this.state = state;
    }

    public long[][] getInitialTeamMemory() {
        return this.state;
    }

    public long[][] getComputedTeamMemory() {
        if (computedTeamMemory == null)
            return this.engine.getTeamMemory();
        else return computedTeamMemory;
    }

    /**
     * @return the number of the most recently computed round, where the first
     *         round is 1 (0 if no rounds have been run yet)
     */
    public int getRoundNumber() {
        return gameWorld.getCurrentRound() + 1;
    }

    /**
     * Cleans up the match so that its resources can be garbage collected.
     */
    public void finish() {
        this.computedTeamMemory = this.engine.getTeamMemory();
        this.gameWorld = null;
        this.engine = null;
    }

    @Override
    public String toString() {
        return String.format("%s vs. %s on %s", info.getTeamA(), info
                .getTeamB(), map);
    }
}
