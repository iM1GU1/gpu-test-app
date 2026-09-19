package nl.s22k.chess.desktop;

import java.util.*;
import nl.s22k.chess.*;
import nl.s22k.chess.engine.*;
import nl.s22k.chess.move.MoveWrapper;
import nl.s22k.chess.search.*;

/** Port of the Android v13 BerserkEngine adapter, with v15-compatible callers. */
public class OverlayBridge {
    public static void main(String[] args) {
        UciOut.noOutput = true;
        UciOptions.setPonder(false);
        UciOptions.setThreadCount(Math.min(4, Runtime.getRuntime().availableProcessors()));
        TTUtil.setSizeMB(64);
        TTUtil.init(false);
        System.out.println("READY");
        Scanner input = new Scanner(System.in);
        while (input.hasNextLine()) {
            String line = input.nextLine();
            if (line.equals("QUIT")) System.exit(0);
            try {
                String[] fields = line.split("\t");
                if (!fields[0].equals("ANALYSE")) continue;
                String fen = fields[1];
                int wanted = Math.max(1, Math.min(3, Integer.parseInt(fields[2])));
                int ms = Math.max(50, Math.min(1000, Integer.parseInt(fields[3])));
                ChessBoard board = ChessBoardInstances.get(0);
                ThreadData data = ThreadData.getInstance(0);
                data.clearHistoryHeuristics();
                List<Integer> excluded = new ArrayList<>();
                StringBuilder output = new StringBuilder("RESULT|");
                for (int i=0; i<wanted; i++) {
                    ChessBoardUtil.setFen(fen, board);
                    data.clearCaches();
                    TTUtil.init(false);
                    // A prior alternative's root entry must never bypass the
                    // root exclusion on a following search.
                    TTUtil.clearValues();
                    NegamaxUtil.setRootExcludedMoves(excluded.stream().mapToInt(n -> n).toArray());
                    MainEngine.pondering = false;
                    MainEngine.maxDepth = EngineConstants.MAX_PLIES;
                    TimeUtil.reset();
                    TimeUtil.setMoveCount(board.moveCounter);
                    TimeUtil.setSimpleTimeWindow(ms * 2L);
                    SearchUtil.start(board);
                    int move = data.getBestMove();
                    if (move == 0 || excluded.contains(move)) break;
                    int score = data.bestScore;
                    String mate = "-";
                    if (Math.abs(score) >= 30000) {
                        int m = (Math.max(0, Util.SHORT_MAX-Math.abs(score))+1)/2;
                        mate = Integer.toString(score >= 0 ? m : -m);
                    }
                    output.append(new MoveWrapper(move)).append(',').append(score).append(',').append(mate).append(';');
                    excluded.add(move);
                }
                System.out.println(output);
            } catch (Throwable error) {
                System.out.println("ERROR|" + error.getClass().getSimpleName()+": "+error.getMessage());
            } finally {
                NegamaxUtil.setRootExcludedMoves(new int[0]);
                NegamaxUtil.isRunning = false;
            }
        }
        System.exit(0);
    }
}
