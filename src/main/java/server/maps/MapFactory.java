/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package server.maps;

import constants.id.MapId;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import scripting.event.EventInstanceManager;
import server.life.AbstractLoadedLife;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.PlayerNPC;
import server.partyquest.GuardianSpawnPoint;
import tools.DatabaseConnection;
import tools.StringUtil;

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static java.util.concurrent.TimeUnit.SECONDS;

public class MapFactory {
    private static final Data nameData;
    private static final DataProvider mapSource;

    static {
        nameData = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Map.img");
        mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);
    }

    private static void loadLifeFromWz(MapleMap map, Data mapData, int world) {
        int[] ids = new int[]{
                100100, 100101, 100120, 100121, 100122, 100123, 100124, 1110100, 1110101, 1120100, 1130100, 1140100, 120100, 1210100, 1210101, 1210102, 2230131, 1110130, 1140130, 1210103, 130100, 130101, 2100100, 2100101, 2100102, 9501018, 9300383, 2100103, 2100104, 2100105, 2100106, 2100107, 210100, 2110200, 2110300, 2110301, 2130100, 2130103, 2220000, 2220100, 2230100, 2230101, 2230102, 2230103, 2230104, 2230105, 2230106, 2230107, 2230108, 2230109, 2230110, 2230111, 2230200, 2300100, 3000000, 3000001, 3000002, 3000003, 3000004, 3000005, 3000006, 3100101, 3100102, 3110100, 3110101, 3110102, 3110300, 3110301, 3110302, 3110303, 3210100, 3210200, 3210201, 3210202, 3210203, 3210204, 3210205, 3210206, 3210207, 3210208, 3210450, 3210800, 3220000, 3220001, 3230100, 3230101, 3230102, 3230103, 3230104, 3230200, 3230300, 3230301, 3230302, 3230303, 3230304, 3230305, 3230306, 3230307, 3230308, 3230400, 3230405, 4090000, 4110300, 4110301, 4110302, 4130100, 4130101, 4130102, 4130103, 4130104, 4220000, 4220001, 4230100, 4230101, 4230102, 4230103, 4230104, 4230105, 4230106, 4230107, 4230108, 4230109, 4230110, 4230111, 4230112, 4230113, 4230114, 4230115, 4230116, 4230117, 4230118, 4230119, 4230120, 4230121, 4230122, 4230123, 4230124, 4230125, 4230126, 4230200, 4230201, 4230300, 4230400, 4230500, 4230501, 4230502, 4230503, 4230504, 4230505, 4230506, 4230600, 4240000, 4250000, 4250001, 5090000, 5090001, 5100000, 5100001, 5100002, 5100003, 5100004, 5100005, 5110300, 5110301, 5110302, 5120000, 5120001, 5120002, 5120003, 5120100, 5120500, 5120501, 5120502, 5120503, 5120504, 5120505, 5120506, 5130100, 5130101, 5130102, 5130103, 5130104, 5130105, 5130106, 5130107, 5130108, 5140000, 5150000, 5150001, 5200000, 5200001, 5200002, 5220000, 5220001, 5220002, 5220003, 5220004, 5250000, 5250001, 5250002, 5300000, 5300001, 5300100, 5400000, 6090000, 6090001, 6090002, 6090003, 6090004, 6110300, 6110301, 6130100, 6130101, 6130102, 6130103, 6130104, 6130200, 6130201, 6130202, 6130203, 6130204, 6130207, 6130208, 6130209, 6220000, 6220001, 6230100, 6230101, 6230200, 6230201, 6230300, 6230400, 6230401, 6230500, 6230600, 6230601, 6230602, 6300000, 6300001, 6300002, 6300003, 6300004, 6300005, 6300100, 6400000, 6400001, 6400002, 6400003, 6400004, 6400005, 6400100, 7090000, 7110300, 7110301, 7130000, 7130001, 7130002, 7130003, 7130004, 7130010, 7130020, 7130100, 7130101, 7130102, 7130103, 7130104, 7130200, 7130300, 7130400, 7130401, 7130402, 7130500, 7130501, 7130600, 7130601, 7130602, 7140000, 7160000, 7220000, 7220001, 7220002, 8090000, 8110300, 8130100, 8140000, 8140001, 8140002, 8140100, 8140101, 8140102, 8140103, 8140110, 8140111, 8140200, 8140300, 8140500, 8140555, 8140600, 8140700, 8140701, 8140702, 8140703, 8141000, 8141100, 8141300, 8142000, 8142100, 8143000, 8150000, 8150100, 8150101, 8150200, 8150201, 8150300, 8150301, 8150302, 8160000, 8170000, 8180000, 8180001, 8190000, 8190001, 8190002, 8190003, 8190004, 8190005, 8200000, 8200001, 8200002, 8200003, 8200004, 8200005, 8200006, 8200007, 8200008, 8200009, 8200010, 8200011, 8200012, 8220000, 8220001, 8220002, 8220003, 8220004, 8220005, 8220006, 8220007, 8220009, 8500000, 8500001, 8500002, 8500003, 8500004, 8510000, 8510100, 8520000, 8800000, 8800001, 8800002, 8800003, 8800004, 8800005, 8800006, 8800007, 8800008, 8800009, 8800010, 8810000, 8810001, 8810002, 8810003, 8810004, 8810005, 8810006, 8810007, 8810008, 8810009, 8810010, 8810011, 8810012, 8810013, 8810014, 8810015, 8810016, 8810017, 8810018, 8810019, 8810020, 8810021, 8810022, 8810023, 8810024, 8810025, 8810026, 8820000, 8820001, 8820002, 8820003, 8820004, 8820005, 8820006, 8820007, 8820008, 8820009, 8820010, 8820011, 8820012, 8820013, 8820014, 8820015, 8820016, 8820017, 8820018, 8820019, 8820020, 8820021, 8820022, 8820023, 8820024, 8820025, 8820026, 8820027, 9000001, 9000002, 9000100, 9000101, 9000200, 9000201, 9000300, 9000301, 9001000, 9001001, 9001002, 9001003, 9001004, 9001005, 9001006, 9001007, 9001008, 9001009, 9001010, 9001011, 9100000, 9100001, 9100002, 9100003, 9100004, 9100005, 9100006, 9100007, 9100008, 9100009, 9100010, 9100013, 9200000, 9200001, 9200002, 9200003, 9200004, 9200005, 9200006, 9200007, 9200008, 9200009, 9200010, 9200011, 9200012, 9200013, 9200014, 9200015, 9200016, 9200017, 9200018, 9200019, 9200020, 9200021, 9200022, 9300000, 9300001, 9300002, 9300003, 9300004, 9300005, 9300006, 9300007, 9300008, 9300009, 9300010, 9300011, 9300012, 9300013, 9300014, 9300015, 9300016, 9300017, 9300018, 9300019, 9300020, 9300021, 9300022, 9300023, 9300024, 9300025, 9300026, 9300027, 9300028, 9300029, 9300030, 9300031, 9300032, 9300033, 9300034, 9300035, 9300036, 9300037, 9300038, 9300039, 9300040, 9300041, 9300042, 9300043, 9300044, 9300045, 9300046, 9300047, 9300048, 9300049, 9300050, 9300051, 9300052, 9300053, 9300054, 9300055, 9300056, 9300057, 9300058, 9300059, 9300060, 9300061, 9300062, 9300063, 9300064, 9300065, 9300066, 9300067, 9300068, 9300069, 9300070, 9300071, 9300072, 9300073, 9300074, 9300075, 9300076, 9300077, 9300078, 9300079, 9300080, 9300081, 9300082, 9300083, 9300084, 9300085, 9300086, 9300087, 9300088, 9300089, 9300090, 9300091, 9300092, 9300093, 9300094, 9300095, 9300096, 9300097, 9300098, 9300099, 9300100, 9300101, 9300102, 9300103, 9300104, 9300105, 9300106, 9300107, 9300108, 9300109, 9300110, 9300111, 9300112, 9300113, 9300114, 9300115, 9300116, 9300117, 9300118, 9300119, 9300120, 9300121, 9300122, 9300123, 9300124, 9300125, 9300126, 9300127, 9300128, 9300129, 9300130, 9300131, 9300132, 9300133, 9300134, 9300135, 9300136, 9300137, 9300138, 9300139, 9300140, 9300141, 9300142, 9300143, 9300144, 9300145, 9300146, 9300147, 9300148, 9300149, 9300150, 9300151, 9300152, 9300153, 9300154, 9300155, 9300156, 9300157, 9300158, 9300159, 9300160, 9300161, 9300162, 9300163, 9300164, 9300165, 9300166, 9300168, 9300169, 9300170, 9300171, 9300172, 9300173, 9300174, 9300175, 9300176, 9300177, 9300178, 9300179, 9300180, 9300181, 9300182, 9300183, 9300184, 9300185, 9300186, 9300187, 9300188, 9300189, 9300190, 9300191, 9300192, 9300193, 9300194, 9300195, 9300196, 9300197, 9300198, 9300199, 9300200, 9300201, 9300202, 9300203, 9300204, 9300205, 9300206, 9300207, 9300208, 9300209, 9300210, 9300211, 9300212, 9300213, 9300214, 9300215, 9300216, 9300217, 9300218, 9300219, 9300220, 9300221, 9300222, 9300223, 9300224, 9300225, 9300226, 9300227, 9300228, 9300229, 9300230, 9300231, 9300232, 9300233, 9300234, 9300235, 9300236, 9300237, 9300238, 9300239, 9300240, 9300241, 9300242, 9300243, 9300244, 9300245, 9300246, 9300247, 9300248, 9300249, 9300250, 9300251, 9300252, 9300253, 9300254, 9300255, 9300256, 9300257, 9300258, 9300259, 9300260, 9300261, 9300262, 9300263, 9300264, 9300265, 9300266, 9300267, 9300268, 9300269, 9300270, 9300271, 9300272, 9300273, 9300274, 9300275, 9300276, 9300277, 9300278, 9300279, 9300280, 9300281, 9300282, 9300283, 9300284, 9300285, 9300286, 9300287, 9300288, 9300289, 9300290, 9300291, 9300292, 9300293, 9300294, 9300295, 9300296, 9300297, 9300298, 9300299, 9300300, 9300301, 9300302, 9300303, 9300304, 9300305, 9300306, 9300307, 9300308, 9300309, 9300310, 9300315, 9300316, 9300317, 9300318, 9300319, 9300320, 9300321, 9300322, 9300323, 9300324, 9300325, 9300328, 9300329, 9300330, 9300331, 9300332, 9300334, 9300335, 9300336, 9300337, 9300338, 9300339, 9300340, 9300341, 9300342, 9300343, 9300344, 9300345, 9300346, 9300347, 9300348, 9300349, 9300350, 9300351, 9300352, 9300353, 9300354, 9300355, 9300356, 9300357, 9300358, 9300359, 9300360, 9300361, 9300362, 9300363, 9300364, 9300365, 9300366, 9300367, 9300368, 9300369, 9300370, 9300371, 9300372, 9300373, 9300374, 9300375, 9300376, 9300377, 9300378, 9300379, 9300380, 9300381, 9300382, 9301000, 9301003, 9301004, 9400000, 9400001, 9400002, 9400003, 9400004, 9400005, 9400006, 9400007, 9400008, 9400009, 9400010, 9400011, 9400012, 9400013, 9400014, 9400100, 9400101, 9400102, 9400103, 9400110, 9400111, 9400112, 9400113, 9400114, 9400120, 9400121, 9400122, 9400200, 9400201, 9400202, 9400203, 9400204, 9400205, 9400209, 9400210, 9400211, 9400212, 9400213, 9400214, 9400215, 9400216, 9400217, 9400218, 9400238, 9400239, 9400240, 9400241, 9400242, 9400243, 9400244, 9400245, 9400246, 9400247, 9400248, 9400249, 9400300, 9400310, 9400311, 9400312, 9400313, 9400314, 9400315, 9400316, 9400317, 9400318, 9400319, 9400320, 9400321, 9400322, 9400323, 9400324, 9400325, 9400326, 9400327, 9400328, 9400329, 9400330, 9400331, 9400332, 9400333, 9400334, 9400335, 9400336, 9400500, 9400501, 9400502, 9400503, 9400504, 9400505, 9400506, 9400507, 9400508, 9400509, 9400510, 9400511, 9400512, 9400513, 9400514, 9400515, 9400516, 9400517, 9400518, 9400519, 9400520, 9400521, 9400522, 9400523, 9400524, 9400525, 9400526, 9400527, 9400528, 9400529, 9400530, 9400531, 9400533, 9400534, 9400535, 9400536, 9400537, 9400538, 9400539, 9400540, 9400541, 9400542, 9400543, 9400544, 9400545, 9400546, 9400547, 9400548, 9400549, 9400550, 9400551, 9400552, 9400553, 9400554, 9400555, 9400556, 9400557, 9400558, 9400559, 9400560, 9400561, 9400562, 9400563, 9400564, 9400565, 9400566, 9400567, 9400568, 9400569, 9400570, 9400571, 9400572, 9400573, 9400574, 9400575, 9400576, 9400577, 9400578, 9400579, 9400580, 9400581, 9400582, 9400583, 9400584, 9400585, 9400586, 9400587, 9400588, 9400589, 9400590, 9400591, 9400592, 9400593, 9400594, 9400595, 9400596, 9400597, 9400598, 9400599, 9400600, 9400601, 9400602, 9400603, 9400604, 9400605, 9400606, 9400607, 9400608, 9400704, 9400706, 9400707, 9400708, 9400709, 9400710, 9400711, 9400712, 9400713, 9400714, 9400715, 9400716, 9400717, 9400718, 9400719, 9400720, 9400721, 9400722, 9400723, 9400724, 9400739, 9400740, 9400741, 9400742, 9400743, 9400744, 9400745, 9400746, 9400747, 9400748, 9400749, 9409000, 9409001, 9410000, 9410001, 9410002, 9410003, 9410004, 9410005, 9410006, 9410007, 9410008, 9410009, 9410010, 9410011, 9410012, 9410013, 9410014, 9410015, 9410016, 9410017, 9410020, 9420000, 9420001, 9420002, 9420003, 9420004, 9420005, 9420015, 9420500, 9420501, 9420502, 9420503, 9420504, 9420505, 9420506, 9420507, 9420508, 9420509, 9420510, 9420511, 9420512, 9420513, 9500000, 9500001, 9500002, 9500003, 9500004, 9500005, 9500100, 9500101, 9500102, 9500103, 9500104, 9500105, 9500106, 9500107, 9500108, 9500109, 9500110, 9500111, 9500112, 9500113, 9500114, 9500115, 9500116, 9500117, 9500118, 9500119, 9500120, 9500121, 9500122, 9500123, 9500124, 9500125, 9500126, 9500127, 9500128, 9500129, 9500130, 9500131, 9500132, 9500133, 9500134, 9500135, 9500136, 9500137, 9500138, 9500139, 9500140, 9500141, 9500142, 9500143, 9500144, 9500145, 9500146, 9500147, 9500148, 9500149, 9500150, 9500151, 9500152, 9500153, 9500154, 9500155, 9500156, 9500157, 9500158, 9500159, 9500160, 9500161, 9500162, 9500163, 9500164, 9500165, 9500166, 9500167, 9500168, 9500169, 9500170, 9500171, 9500172, 9500173, 9500174, 9500175, 9500176, 9500177, 9500178, 9500179, 9500180, 9500181, 9500182, 9500183, 9500184, 9500185, 9500186, 9500187, 9500188, 9500189, 9500190, 9500191, 9500192, 9500193, 9500194, 9500195, 9500196, 9500197, 9500198, 9500199, 9500200, 9500201, 9500202, 9500203, 9500204, 9500300, 9500301, 9500302, 9500303, 9500304, 9500305, 9500306, 9500307, 9500308, 9500309, 9500310, 9500311, 9500312, 9500313, 9500314, 9500315, 9500316, 9500317, 9500318, 9500319, 9500320, 9500321, 9500322, 9500323, 9500324, 9500325, 9500326, 9500327, 9500328, 9500329, 9500330, 9500331, 9500332, 9500333, 9500334, 9500335, 9500336, 9500337, 9500338, 9500339, 9500340, 9500341, 9500342, 9500343, 9500344, 9500345, 9500346, 9500347, 9500348, 9500349, 9500350, 9500351, 9500352, 9500353, 9500354, 9500355, 9500356, 9500357, 9500358, 9500359, 9500360, 9500361, 9500362, 9500363, 9500364, 9500365, 9500366, 9500367, 9500368, 9500369, 9500370, 9500371, 9500372, 9500373, 9500400, 9501000, 9501001, 9501002, 9501003, 9501004, 9501005, 9501006, 9501007, 9501008, 9501009, 9501010, 9501011, 9501012, 9501013, 9501014, 9501015, 9501016, 9501017, 9600001, 9600002, 9600003, 9600004, 9600005, 9600006, 9600007, 9600008, 9600009, 9600010, 9600065, 9600066, 9999998, 9999999, 9400630, 9400631, 9400632, 9420527, 9420528, 9420529, 9420530, 9420531, 9420532, 9420533, 9420534, 9420535, 9420536, 9420537, 9420538, 9420539, 9420540, 9420541, 9420542, 9420543, 9420544, 9420545, 9420546, 9420547, 9420548, 9420549, 9420550, 6400006, 6400007, 6400008, 6400009, 8830000, 8830001, 8830002, 8830003, 8830004, 8830005, 8830006, 8830007, 8830008, 8830009, 8830010, 8830011, 8830012, 8830013, 9300311, 9300312, 9300313, 9300314, 9300326, 9300327, 9302011, 9001013, 9001014, 9303000, 9303001, 9303002, 9303003, 9303004, 9303005, 9303006, 9303007, 9303008, 9303009, 9303010, 9303011, 9303012, 9303013, 9303014, 9303015, 9303016, 9302000, 9302001, 9302002, 9302003, 9302004, 9302005, 9302006, 9302007, 9302008, 9302009, 9302010, 100130, 100131, 100132, 100133, 100134, 9400609, 9400610, 9400611, 9400612, 9400613, 9400614, 9400615, 9400616, 9400617, 9400618, 9400619, 9400620, 9400621, 9400622, 9400623, 9400633, 9400645, 9400646, 9400647, 9400634, 9400635, 9400636, 9400637, 9400638, 9400639, 9400640, 9400641, 9400642, 9400643, 9400644, 9400648, 9400649, 9400650, 9400651, 9400652, 9400653, 9400654, 9400655, 9400656, 9400657, 3300000, 3300001, 3300002, 3300003, 3300004, 3300005, 3300006, 3300007, 3300008, 9101000, 9101001, 9101002, 9700000, 9700001, 9700002, 9700003, 9700004, 9700005, 9700006, 9700007, 9700008, 9700009, 9700010, 9700011, 9700012, 9700013, 9700014, 9700015, 9700017, 9700016, 9700018, 9700019, 9700020, 9700021, 9700022, 9700023, 9700024, 9700025, 9700026, 9700027, 9700028, 9700029, 8220008, 9001012, 1210111, 2220110, 3400000, 3400001, 3400002, 3400003, 3400004, 3400005, 3400006, 3400007, 3400008, 4300000, 4300001, 4300002, 4300003, 4300004, 4300005, 4300006, 4300007, 4300008, 4300009, 4300010, 4300011, 4300012, 4300013, 4300014, 4300015, 4300016, 4300017, 7120100, 7120101, 7120102, 7120103, 7120104, 7120105, 7120106, 7120107, 7120108, 7120109, 7220003, 7220004, 7220005, 8120100, 8120101, 8120102, 8120103, 8120104, 8120105, 8120106, 8120107, 8140510, 8140511, 8140512, 8220010, 8220011, 8220012, 8220013, 8220014, 8220015, 9300384, 9300385, 9300387, 9300391, 9300393, 9700030, 9700031, 9700032, 9700033, 9700034, 9700035, 9700036, 9700037, 9700038, 2100108,
        };
        Random r = new Random();
        Map<String, String> replace = new HashMap<>();
        for (Data life : mapData.getChildByPath("life")) {
            life.getName();
            String id = DataTool.getString(life.getChildByPath("id"));
            int originalId = Integer.parseInt(id);
            String type = DataTool.getString(life.getChildByPath("type"));
            if (world == 1 && type.equals("m")) {
                if (replace.containsKey(id)) {
                    id = replace.get(id);
                } else {
                    String nid = String.valueOf(ids[r.nextInt(0, ids.length - 1)]);
                    Monster monster = LifeFactory.getMonster(Integer.parseInt(nid));
                    while (monster == null || monster.isBoss()) {
                        nid = String.valueOf(ids[r.nextInt(0, ids.length - 1)]);
                        monster = LifeFactory.getMonster(Integer.parseInt(nid));
                    }
                    replace.put(id, nid);
                    id = nid;
                }
            }
            int team = DataTool.getInt("team", life, -1);
            if (map.isCPQMap2() && type.equals("m")) {
                if ((Integer.parseInt(life.getName()) % 2) == 0) {
                    team = 0;
                } else {
                    team = 1;
                }
            }
            int cy = DataTool.getInt(life.getChildByPath("cy"));
            Data dF = life.getChildByPath("f");
            int f = (dF != null) ? DataTool.getInt(dF) : 0;
            int fh = DataTool.getInt(life.getChildByPath("fh"));
            int rx0 = DataTool.getInt(life.getChildByPath("rx0"));
            int rx1 = DataTool.getInt(life.getChildByPath("rx1"));
            int x = DataTool.getInt(life.getChildByPath("x"));
            int y = DataTool.getInt(life.getChildByPath("y"));
            int hide = DataTool.getInt("hide", life, 0);
            int mobTime = DataTool.getInt("mobTime", life, 0);

            loadLifeRaw(map, Integer.parseInt(id), type, cy, f, fh, rx0, rx1, x, y, hide, mobTime, team, world, originalId);
        }
    }

    private static void loadLifeFromDb(MapleMap map, int world) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM plife WHERE map = ? and world = ?")) {
            ps.setInt(1, map.getId());
            ps.setInt(2, map.getWorld());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("life");
                    String type = rs.getString("type");
                    int cy = rs.getInt("cy");
                    int f = rs.getInt("f");
                    int fh = rs.getInt("fh");
                    int rx0 = rs.getInt("rx0");
                    int rx1 = rs.getInt("rx1");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int hide = rs.getInt("hide");
                    int mobTime = rs.getInt("mobtime");
                    int team = rs.getInt("team");

                    loadLifeRaw(map, id, type, cy, f, fh, rx0, rx1, x, y, hide, mobTime, team, world, id);
                }
            }
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }
    }

    private static void loadLifeRaw(MapleMap map, int id, String type, int cy, int f, int fh, int rx0, int rx1, int x, int y, int hide, int mobTime, int team, int world, int originalId) {
        AbstractLoadedLife myLife = loadLife(id, type, cy, f, fh, rx0, rx1, x, y, hide);
        if (myLife instanceof Monster monster) {

            if (mobTime == -1) { //does not respawn, force spawn once
                map.spawnMonster(monster);
            } else {
                map.addMonsterSpawn(monster, mobTime, team);
            }
            if (world == 1) {
                monster.setOriginalId(originalId);
            }

            //should the map be reseted, use allMonsterSpawn list of monsters to spawn them again
            map.addAllMonsterSpawn(monster, mobTime, team);
        } else {
            map.addMapObject(myLife);
        }
    }

    public static MapleMap loadMapFromWz(int mapid, int world, int channel, EventInstanceManager event) {
        MapleMap map;

        String mapName = getMapName(mapid);
        Data mapData = mapSource.getData(mapName);    // source.getData issue with giving nulls in rare ocasions found thanks to MedicOP
        Data infoData = mapData.getChildByPath("info");

        String link = DataTool.getString(infoData.getChildByPath("link"), "");
        if (!link.equals("")) { //nexon made hundreds of dojo maps so to reduce the size they added links.
            mapName = getMapName(Integer.parseInt(link));
            mapData = mapSource.getData(mapName);
        }
        float monsterRate = 0;
        Data mobRate = infoData.getChildByPath("mobRate");
        if (mobRate != null) {
            monsterRate = (Float) mobRate.getData();
        }
        map = new MapleMap(mapid, world, channel, DataTool.getInt("returnMap", infoData), monsterRate);
        map.setEventInstance(event);

        String onFirstEnter = DataTool.getString(infoData.getChildByPath("onFirstUserEnter"), String.valueOf(mapid));
        map.setOnFirstUserEnter(onFirstEnter.equals(String.valueOf(mapid)) ? "default" : onFirstEnter);

        String onEnter = DataTool.getString(infoData.getChildByPath("onUserEnter"), String.valueOf(mapid));
        map.setOnUserEnter(onEnter.equals("") ? String.valueOf(mapid) : onEnter);

        map.setFieldLimit(DataTool.getInt(infoData.getChildByPath("fieldLimit"), 0));
        map.setMobInterval((short) DataTool.getInt(infoData.getChildByPath("createMobInterval"), 5000));
        PortalFactory portalFactory = new PortalFactory();
        for (Data portal : mapData.getChildByPath("portal")) {
            map.addPortal(portalFactory.makePortal(DataTool.getInt(portal.getChildByPath("pt")), portal));
        }
        Data timeMob = infoData.getChildByPath("timeMob");
        if (timeMob != null) {
            map.setTimeMob(DataTool.getInt(timeMob.getChildByPath("id")), DataTool.getString(timeMob.getChildByPath("message")));
        }

        int[] bounds = new int[4];
        bounds[0] = DataTool.getInt(infoData.getChildByPath("VRTop"));
        bounds[1] = DataTool.getInt(infoData.getChildByPath("VRBottom"));

        if (bounds[0] == bounds[1]) {    // old-style baked map
            Data minimapData = mapData.getChildByPath("miniMap");
            if (minimapData != null) {
                bounds[0] = DataTool.getInt(minimapData.getChildByPath("centerX")) * -1;
                bounds[1] = DataTool.getInt(minimapData.getChildByPath("centerY")) * -1;
                bounds[2] = DataTool.getInt(minimapData.getChildByPath("height"));
                bounds[3] = DataTool.getInt(minimapData.getChildByPath("width"));

                map.setMapPointBoundings(bounds[0], bounds[1], bounds[2], bounds[3]);
            } else {
                int dist = (1 << 18);
                map.setMapPointBoundings(-dist / 2, -dist / 2, dist, dist);
            }
        } else {
            bounds[2] = DataTool.getInt(infoData.getChildByPath("VRLeft"));
            bounds[3] = DataTool.getInt(infoData.getChildByPath("VRRight"));

            map.setMapLineBoundings(bounds[0], bounds[1], bounds[2], bounds[3]);
        }

        List<Foothold> allFootholds = new LinkedList<>();
        Point lBound = new Point();
        Point uBound = new Point();
        for (Data footRoot : mapData.getChildByPath("foothold")) {
            for (Data footCat : footRoot) {
                for (Data footHold : footCat) {
                    int x1 = DataTool.getInt(footHold.getChildByPath("x1"));
                    int y1 = DataTool.getInt(footHold.getChildByPath("y1"));
                    int x2 = DataTool.getInt(footHold.getChildByPath("x2"));
                    int y2 = DataTool.getInt(footHold.getChildByPath("y2"));
                    Foothold fh = new Foothold(new Point(x1, y1), new Point(x2, y2), Integer.parseInt(footHold.getName()));
                    fh.setPrev(DataTool.getInt(footHold.getChildByPath("prev")));
                    fh.setNext(DataTool.getInt(footHold.getChildByPath("next")));
                    if (fh.getX1() < lBound.x) {
                        lBound.x = fh.getX1();
                    }
                    if (fh.getX2() > uBound.x) {
                        uBound.x = fh.getX2();
                    }
                    if (fh.getY1() < lBound.y) {
                        lBound.y = fh.getY1();
                    }
                    if (fh.getY2() > uBound.y) {
                        uBound.y = fh.getY2();
                    }
                    allFootholds.add(fh);
                }
            }
        }
        FootholdTree fTree = new FootholdTree(lBound, uBound);
        for (Foothold fh : allFootholds) {
            fTree.insert(fh);
        }
        map.setFootholds(fTree);
        if (mapData.getChildByPath("area") != null) {
            for (Data area : mapData.getChildByPath("area")) {
                int x1 = DataTool.getInt(area.getChildByPath("x1"));
                int y1 = DataTool.getInt(area.getChildByPath("y1"));
                int x2 = DataTool.getInt(area.getChildByPath("x2"));
                int y2 = DataTool.getInt(area.getChildByPath("y2"));
                map.addMapleArea(new Rectangle(x1, y1, (x2 - x1), (y2 - y1)));
            }
        }
        if (mapData.getChildByPath("seat") != null) {
            int seats = mapData.getChildByPath("seat").getChildren().size();
            map.setSeats(seats);
        }
        if (event == null) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT * FROM playernpcs WHERE map = ? AND world = ?")) {
                ps.setInt(1, mapid);
                ps.setInt(2, world);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.addPlayerNPCMapObject(new PlayerNPC(rs));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        loadLifeFromWz(map, mapData, world);
        loadLifeFromDb(map, world);

        if (map.isCPQMap()) {
            Data mcData = mapData.getChildByPath("monsterCarnival");
            if (mcData != null) {
                map.setDeathCP(DataTool.getIntConvert("deathCP", mcData, 0));
                map.setMaxMobs(DataTool.getIntConvert("mobGenMax", mcData, 20));    // thanks Atoot for noticing CPQ1 bf. 3 and 4 not accepting spawns due to undefined limits, Lame for noticing a need to cap mob spawns even on such undefined limits
                map.setTimeDefault(DataTool.getIntConvert("timeDefault", mcData, 0));
                map.setTimeExpand(DataTool.getIntConvert("timeExpand", mcData, 0));
                map.setMaxReactors(DataTool.getIntConvert("guardianGenMax", mcData, 16));
                Data guardianGenData = mcData.getChildByPath("guardianGenPos");
                for (Data node : guardianGenData.getChildren()) {
                    GuardianSpawnPoint pt = new GuardianSpawnPoint(new Point(DataTool.getIntConvert("x", node), DataTool.getIntConvert("y", node)));
                    pt.setTeam(DataTool.getIntConvert("team", node, -1));
                    pt.setTaken(false);
                    map.addGuardianSpawnPoint(pt);
                }
                if (mcData.getChildByPath("skill") != null) {
                    for (Data area : mcData.getChildByPath("skill")) {
                        map.addSkillId(DataTool.getInt(area));
                    }
                }

                if (mcData.getChildByPath("mob") != null) {
                    for (Data area : mcData.getChildByPath("mob")) {
                        map.addMobSpawn(DataTool.getInt(area.getChildByPath("id")), DataTool.getInt(area.getChildByPath("spendCP")));
                    }
                }
            }

        }

        if (mapData.getChildByPath("reactor") != null) {
            for (Data reactor : mapData.getChildByPath("reactor")) {
                String id = DataTool.getString(reactor.getChildByPath("id"));
                if (id != null) {
                    Reactor newReactor = loadReactor(reactor, id, (byte) DataTool.getInt(reactor.getChildByPath("f"), 0));
                    map.spawnReactor(newReactor);
                }
            }
        }

        map.setMapName(loadPlaceName(mapid));
        map.setStreetName(loadStreetName(mapid));

        map.setClock(mapData.getChildByPath("clock") != null);
        map.setEverlast(DataTool.getIntConvert("everlast", infoData, 0) != 0); // thanks davidlafriniere for noticing value 0 accounting as true
        map.setTown(DataTool.getIntConvert("town", infoData, 0) != 0);
        map.setHPDec(DataTool.getIntConvert("decHP", infoData, 0));
        map.setHPDecProtect(DataTool.getIntConvert("protectItem", infoData, 0));
        map.setForcedReturnMap(DataTool.getInt(infoData.getChildByPath("forcedReturn"), MapId.NONE));
        map.setBoat(mapData.getChildByPath("shipObj") != null);
        map.setTimeLimit(DataTool.getIntConvert("timeLimit", infoData, -1));
        map.setFieldType(DataTool.getIntConvert("fieldType", infoData, 0));
        map.setMobCapacity(DataTool.getIntConvert("fixedMobCapacity", infoData, 500));//Is there a map that contains more than 500 mobs?

        Data recData = infoData.getChildByPath("recovery");
        if (recData != null) {
            map.setRecovery(DataTool.getFloat(recData));
        }

        HashMap<Integer, Integer> backTypes = new HashMap<>();
        try {
            for (Data layer : mapData.getChildByPath("back")) { // yolo
                int layerNum = Integer.parseInt(layer.getName());
                int btype = DataTool.getInt(layer.getChildByPath("type"), 0);

                backTypes.put(layerNum, btype);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // swallow cause I'm cool
        }

        map.setBackgroundTypes(backTypes);
        map.generateMapDropRangeCache();

        return map;
    }

    private static AbstractLoadedLife loadLife(int id, String type, int cy, int f, int fh, int rx0, int rx1, int x, int y, int hide) {
        AbstractLoadedLife myLife = LifeFactory.getLife(id, type);
        myLife.setCy(cy);
        myLife.setF(f);
        myLife.setFh(fh);
        myLife.setRx0(rx0);
        myLife.setRx1(rx1);
        myLife.setPosition(new Point(x, y));
        if (hide == 1) {
            myLife.setHide(true);
        }
        return myLife;
    }

    private static Reactor loadReactor(Data reactor, String id, final byte FacingDirection) {
        Reactor myReactor = new Reactor(ReactorFactory.getReactor(Integer.parseInt(id)), Integer.parseInt(id));
        int x = DataTool.getInt(reactor.getChildByPath("x"));
        int y = DataTool.getInt(reactor.getChildByPath("y"));
        myReactor.setFacingDirection(FacingDirection);
        myReactor.setPosition(new Point(x, y));
        myReactor.setDelay((int) SECONDS.toMillis(DataTool.getInt(reactor.getChildByPath("reactorTime"))));
        myReactor.setName(DataTool.getString(reactor.getChildByPath("name"), ""));
        myReactor.resetReactorActions(0);
        return myReactor;
    }

    private static String getMapName(int mapid) {
        String mapName = StringUtil.getLeftPaddedStr(Integer.toString(mapid), '0', 9);
        StringBuilder builder = new StringBuilder("Map/Map");
        int area = mapid / 100000000;
        builder.append(area);
        builder.append("/");
        builder.append(mapName);
        builder.append(".img");
        mapName = builder.toString();
        return mapName;
    }

    private static String getMapStringName(int mapid) {
        StringBuilder builder = new StringBuilder();
        if (mapid < 100000000) {
            builder.append("maple");
        } else if (mapid >= 100000000 && mapid < MapId.ORBIS) {
            builder.append("victoria");
        } else if (mapid >= MapId.ORBIS && mapid < MapId.ELLIN_FOREST) {
            builder.append("ossyria");
        } else if (mapid >= MapId.ELLIN_FOREST && mapid < 400000000) {
            builder.append("elin");
        } else if (mapid >= MapId.SINGAPORE && mapid < 560000000) {
            builder.append("singapore");
        } else if (mapid >= MapId.NEW_LEAF_CITY && mapid < 620000000) {
            builder.append("MasteriaGL");
        } else if (mapid >= 677000000 && mapid < 677100000) {
            builder.append("Episode1GL");
        } else if (mapid >= 670000000 && mapid < 682000000) {
            if ((mapid >= 674030000 && mapid < 674040000) || (mapid >= 680100000 && mapid < 680200000)) {
                builder.append("etc");
            } else {
                builder.append("weddingGL");
            }
        } else if (mapid >= 682000000 && mapid < 683000000) {
            builder.append("HalloweenGL");
        } else if (mapid >= 683000000 && mapid < 684000000) {
            builder.append("event");
        } else if (mapid >= MapId.MUSHROOM_SHRINE && mapid < 900000000) {
            if ((mapid >= 889100000 && mapid < 889200000)) {
                builder.append("etc");
            } else {
                builder.append("jp");
            }
        } else {
            builder.append("etc");
        }
        builder.append("/").append(mapid);
        return builder.toString();
    }

    public static String loadPlaceName(int mapid) {
        try {
            return DataTool.getString("mapName", nameData.getChildByPath(getMapStringName(mapid)), "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String loadStreetName(int mapid) {
        try {
            return DataTool.getString("streetName", nameData.getChildByPath(getMapStringName(mapid)), "");
        } catch (Exception e) {
            return "";
        }
    }

}
