/**
 * Casino Chip Exchange NPC (9000055)
 * Opens the casino chip shop where players can buy and sell
 * stamp chips at equal prices for lossless meso exchange.
 *
 * Shop ID 9999001 must exist in the database (shops + shopitems tables).
 * Seeded by Liquibase: db/data/171-casino-shop-data.sql
 *
 * Chip prices (must match CasinoChipConfig.java):
 *   4002000 Snail Stamp       = 10,000 mesos
 *   4002001 Blue Snail Stamp  = 50,000 mesos
 *   4002002 Stump Stamp       = 250,000 mesos
 *   4002003 Slime Stamp       = 1,000,000 mesos
 */
function start() {
    cm.openShopNPC(9999001);
    cm.dispose();
}
