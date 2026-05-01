package App;

import App.crdt.action.Action;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;


public class UndoRedoManager {

    private static final int MAX_HISTORY = 200;

    private final Deque<List<Action>> undoStack = new ArrayDeque<>();
    private final Deque<List<Action>> redoStack  = new ArrayDeque<>();

    public void pushUndo(List<Action> actions) {
        if (actions == null || actions.isEmpty()) return;
        undoStack.push(new ArrayList<>(actions));
        redoStack.clear();
        trimStack(undoStack);
    }
    public void pushUndoKeepRedo(List<Action> actions) {
        if (actions == null || actions.isEmpty()) return;
        undoStack.push(new ArrayList<>(actions));
        trimStack(undoStack);
    }
    public void pushRedo(List<Action> actions) {
        if (actions == null || actions.isEmpty()) return;
        redoStack.push(new ArrayList<>(actions));
        trimStack(redoStack);
    }

    public List<Action> popUndo() {
        return undoStack.isEmpty() ? null : undoStack.pop();
    }

    public List<Action> popRedo() {
        return redoStack.isEmpty() ? null : redoStack.pop();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    private void trimStack(Deque<List<Action>> stack) {
        while (stack.size() > MAX_HISTORY) {
            ((ArrayDeque<List<Action>>) stack).pollLast();
        }
    }
}