package cn.piq.retro.client;

/** Physical presence decides capture; runtime authorization separately decides the emitted mask. */
public final class KeyboardRouting {
    public enum Route { PASS,SETTINGS,TOGGLE,GAME,MOVEMENT,WORLD }
    private KeyboardRouting(){}
    /** Existing admitted input wins; capture-only may be replaced only by an admitted lane. */
    public static boolean mayReplace(boolean currentPresent,boolean currentAuthorized,boolean incomingPresent,boolean incomingAuthorized){
        return incomingPresent&&(!currentPresent||(!currentAuthorized&&incomingAuthorized));
    }
    public static Route route(int key,int settings,int toggle,boolean focused,boolean owner,boolean authorized,
                              KeyboardControlState.Mode mode,boolean game,boolean movement){
        return route(key,settings,toggle,focused,owner,authorized,mode,game,false,movement);
    }
    public static Route route(int key,int settings,int toggle,boolean focused,boolean owner,boolean present,
                              KeyboardControlState.Mode mode,boolean game,boolean directionOnly,boolean movement){
        if(!focused||key==256||key< -1)return Route.PASS;
        // Unknown/OEM keys cannot match disabled hotkeys; only a real movement binding is captured.
        if(key==-1)return owner&&present&&mode==KeyboardControlState.Mode.LOCKED&&movement?Route.MOVEMENT:Route.PASS;
        if(key==settings)return Route.SETTINGS;
        if(!owner||!present)return Route.PASS;
        if(key==toggle)return Route.TOGGLE;
        if(game&&(!directionOnly||!movement||mode==KeyboardControlState.Mode.LOCKED))return Route.GAME;
        return mode==KeyboardControlState.Mode.LOCKED&&movement?Route.MOVEMENT:Route.PASS;
    }
}
