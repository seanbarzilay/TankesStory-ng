function start(ms) {
    var world = ms.getClient().getChannelServer().getWorld()
    if (world == 1) {
        var mid = ms.getPlayer().getMapId()
        var newMap = ms.getClient().getChannelServer().getMapFactory().resetMap(mid)
        ms.getClient().getPlayer().getAllPlayers().forEach(player => {
            player.saveLocationOnWarp()
            player.changeMap(newMap)
        })
        newMap.respawn()
	ms.getPlayer().message("map reset")
    }
}
