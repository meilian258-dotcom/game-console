// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.flow;

/** Pure authority-side lifecycle. UI, native cores and storage are adapters, never state owners. */
public final class DeviceSessionFlow {
    public enum Stage { PREPARING, SAVE_SELECTION, VALIDATING, JOIN_CONFIRM, LOADING, READY, STOPPING, CLOSED, CANCELLED, FAILED }
    private final int maxPlayers;
    private final boolean saving,privatePlay;
    private Stage stage=Stage.PREPARING;
    private int savePlayers=1;
    private boolean allowSecondPort;
    private long revision=1;
    public DeviceSessionFlow(int maxPlayers,boolean saving,boolean privatePlay){
        if(maxPlayers<1||maxPlayers>2)throw new IllegalArgumentException("Home port limit");
        this.maxPlayers=maxPlayers;this.saving=saving;this.privatePlay=privatePlay;
    }
    public Stage stage(){return stage;}
    public long revision(){return revision;}
    public int maxPlayers(){return maxPlayers;}
    public int savePlayers(){return savePlayers;}
    public boolean allowSecondPort(){return allowSecondPort;}
    public boolean terminal(){return stage==Stage.CLOSED||stage==Stage.CANCELLED||stage==Stage.FAILED;}
    public boolean accepts(long expected){return expected==revision&&!terminal();}
    private void move(Stage next){stage=next;revision++;}
    public void prepared(){require(Stage.PREPARING);move(saving?Stage.SAVE_SELECTION:Stage.VALIDATING);}
    public void select(int players){require(Stage.SAVE_SELECTION);if(players<1||players>maxPlayers)throw new IllegalArgumentException("Save label exceeds content ports");savePlayers=players;move(Stage.VALIDATING);}
    public void selected(){require(Stage.VALIDATING);allowSecondPort=false;move(!privatePlay&&maxPlayers>1?Stage.JOIN_CONFIRM:Stage.LOADING);}
    public void invalidSelection(){require(Stage.VALIDATING);if(!saving)throw new IllegalStateException("No save selection");move(Stage.SAVE_SELECTION);}
    public void join(boolean allowed){require(Stage.JOIN_CONFIRM);allowSecondPort=allowed;move(Stage.LOADING);}
    public void back(){require(Stage.JOIN_CONFIRM);if(!saving)throw new IllegalStateException("No previous save page");allowSecondPort=false;move(Stage.SAVE_SELECTION);}
    public void ready(){require(Stage.LOADING);move(Stage.READY);}
    public void stopping(){if(stage!=Stage.READY&&stage!=Stage.LOADING)throw new IllegalStateException("Not running");allowSecondPort=false;move(Stage.STOPPING);}
    public void finished(boolean success){require(Stage.STOPPING);move(success?Stage.CLOSED:Stage.FAILED);}
    public void cancel(){if(terminal())return;if(stage==Stage.READY||stage==Stage.STOPPING)throw new IllegalStateException("Running sessions need durable close");allowSecondPort=false;move(Stage.CANCELLED);}
    public void fail(){if(!terminal()){allowSecondPort=false;move(Stage.FAILED);}}
    private void require(Stage expected){if(stage!=expected)throw new IllegalStateException("Expected "+expected+", was "+stage);}
}
