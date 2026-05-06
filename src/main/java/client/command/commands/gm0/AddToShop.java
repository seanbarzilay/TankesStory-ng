package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ShopFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class AddToShop extends Command {
    {
        setDescription("Add an item to the shop.");
    }
    private static final Logger log = LoggerFactory.getLogger(AddToShop.class);

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        if (params.length < 3) {
            String sendStr = "Syntax: #b@addtoshop <shopname> <itemid> <price>#k. \r\n#r" +
                    "Available shops:#k\r\n" +
                    "charizard (Hat Scrolls Seller)\r\n" +
                    "venusaur (Torso Scrolls Seller)\r\n" +
                    "blastoise (Overall Scrolls Seller)\r\n" +
                    "snorlax (Bottomwear Scrolls Seller)\r\n" +
                    "gengar (Shoes Scrolls Seller)\r\n" +
                    "dragonite (Gloves Scrolls Seller)\r\n" +
                    "machamp (Shield Scrolls Seller)\r\n" +
                    "ditto (General Store)\r\n" +
                    "lugia (Weapons Scrolls Store)\r\n" +
                    "poliwrath (Ores Seller)\r\n" +
                    "wobbuffet (Crystals Seller)\r\n" +
                    "\r\nExample:\r\n#r@addtoshop charizard 2000004 1000:#k\r\nwill add an Elixir for 1000 meso to Charizard's shop";

            player.getAbstractPlayerInteraction().npcTalk(11003, sendStr);
            return;
        }
        int shopId;
        String shopName = params[0];
        switch (shopName) {
            case "charizard":
                shopId = 10000000;
                break;
            case "venusaur":
                shopId = 10000001;
                break;
            case "blastoise":
                shopId = 10000002;
                break;
            case "snorlax":
                shopId = 10000003;
                break;
            case "gengar":
                shopId = 10000004;
                break;
            case "dragonite":
                shopId = 10000005;
                break;
            case "machamp":
                shopId = 10000006;
                break;
            case "ditto":
                shopId = 10000007;
                break;
            case "lugia":
                shopId = 10000008;
                break;
            case "poliwrath":
                shopId = 10000009;
                break;
            case "wobbuffet":
                shopId = 10000010;
                break;
            case "psyduck":
                shopId = 10000011;
                break;
            default:
                String sendStr = "Error: shop: #b" + shopName + "#k not found. \r\n#rAvailable shops:#k\r\ncharizard";

                player.getAbstractPlayerInteraction().npcTalk(11003, sendStr);
                return;
        }
        int itemId = 0;
        int price = 0;
        int position = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            itemId = Integer.parseInt(params[1]);
            price = Integer.parseInt(params[2]);
            position = 0;

            try (PreparedStatement ps = con.prepareStatement("select coalesce(max(position), -1) from shopitems where shopid=?", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, shopId);    // thanks CanIGetaPR for noticing an unnecessary "level" limitation when persisting DB data

                ResultSet res = ps.executeQuery();
                res.next();
                position = res.getInt(1) + 1;
            }

            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);

            try {
                try (PreparedStatement ps = con.prepareStatement("insert into shopitems (shopid, itemid, price, position) values (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, shopId);    // thanks CanIGetaPR for noticing an unnecessary "level" limitation when persisting DB data
                    ps.setInt(2, itemId);
                    ps.setInt(3, price);
                    ps.setInt(4, position);

                    ps.execute();
                }

                con.commit();
                player.dropMessage("You have successfully added an item: "+ itemId +" to "+ shopName +"'s shop.");
                log.info("Added item {} to shop {}", itemId, shopId);
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                con.setAutoCommit(true);
                ShopFactory.getInstance().reloadShops();
            }
        } catch (Exception e) {
            player.dropMessage("Error adding an item: "+ params[1] + " to shop: " + params[0]);
            log.error("Error adding item: shop :{}, item: {}, price: {}, position: {}", shopId, itemId, price, position, e);
        }
    }
}

