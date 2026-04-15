package App; // Must match your folder name

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import App.crdt.action.Action;
import App.crdt.character.CharDLL;
import App.crdt.block.BlockDLL;


@SpringBootApplication
public class ServerMain {
    public static void main(String[] args) {
        SpringApplication.run(ServerMain.class, args);
    }

}