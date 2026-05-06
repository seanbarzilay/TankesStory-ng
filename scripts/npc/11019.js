/**
 * NX Vendor NPC Script
 * Purpose: Allows players to buy NX with three options (1000, 10000, 100000)
 */

var status = 0;
var nxOptions = [1000, 10000, 100000];
var costPerNx = 1000; // Example: 1 mesos per 1 NX (adjust as needed)
var selectedOption = -1;
function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
        return;
    }
    if (mode == 0) {
        cm.sendOk("Come back when you're ready to purchase NX!");
        cm.dispose();
        return;
    }
    if (mode == 1) {
        status++;
    }

    if (status == 0) {
        cm.sendSimple("Hello! Would you like to buy some NX? Please choose an amount:\r\n" +
            "#L0# 1,000 NX#l\r\n" +
            "#L1# 10,000 NX#l\r\n" +
            "#L2# 100,000 NX#l");
    } else if (status == 1) {
        var nxAmount = nxOptions[selection];
        var cost = nxAmount * costPerNx;
        selectedOption = nxAmount;
        cm.sendYesNo("You selected " + nxAmount + " NX for " + cost + " mesos. Would you like to proceed with the purchase?");
    } else if (status == 2) {
        var nxAmount = selectedOption;
        var cost = nxAmount * costPerNx;
        if (cm.getMeso() >= cost) {
            cm.gainMeso(-cost);
            cm.getPlayer().getCashShop().gainCash(1, nxAmount); // 0 for NX Cash (adjust type if needed)
            cm.sendOk("Thank you for your purchase! You have received " + nxAmount + " NX.");
        } else {
            cm.sendOk("You don't have enough mesos to buy " + nxAmount + " NX. You need " + cost + " mesos.");
        }
        cm.dispose();
    }
}
