package App.crdt.block;

import App.crdt.action.Action;

public class Error_Debugging {

    public static void main(String[] args) {
        BlockDLL blockDLL = new BlockDLL();
        String documentId = "debug-doc";
        int siteId = 1;
        long clock = 0;
        long time = System.currentTimeMillis();

        String root = blockDLL.ensureSeedHeadID();

        // Build "omar"
        String parentCharId = root;
        for (char c : "omar".toCharArray()) {
            Action letter = insertAction(clock++, time + clock, siteId, documentId, parentCharId, c);
            blockDLL.applyAction(letter);
            parentCharId = charId(siteId, letter.getClock());
        }

        // Insert many newlines before "omar" by chaining from root.
        String newlineParent = root;
        for (int i = 0; i < 30; i++) {
            Action newline = insertAction(clock++, time + 10_000 + i, siteId, documentId, newlineParent, '\n');
            blockDLL.applyAction(newline);
            newlineParent = charId(siteId, newline.getClock());
            System.out.println(blockDLL.collectText().replace("\n", "\\n"));
        }


        String text = blockDLL.collectText();
        System.out.println("Final text (raw):");
        System.out.println(text);
        System.out.println("Final text (escaped): " + text.replace("\n", "\\n"));
        System.out.println("Contains 'omar': " + text.contains("omar"));
    }

    private static Action insertAction(long clock, long time, int siteId, String documentId, String parentCharId, char value) {
        return new Action(
                clock,
                time,
                siteId,
                documentId,
                "INSERT",
                parentCharId,
                null,
                String.valueOf(value)
        );
    }

    private static String charId(int siteId, long clock) {
        return siteId + "-" + clock;
    }
}
