package p;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;

public class JsonTradeHistoryParser {
	//Elde kalanların satılması gereken fiyat: (ToplamAlisTutari-ToplamSatisTutari)/(toplamAlisLot-ToplamSatisLot)   + 0,15
	
	private static final String INPUT_FILE = "20250430-20250528.json";
	//private static final String INPUT_FILE = "20250528tmp.json";
    private static final String OUTPUT_FILE = "output.csv";
    private static final double MARJ_MIN = 0.139;
    private static final double MARJ_MAX = 0.171;
    private static final double COMMISSION_RATE = 1.478 / 10_000;
    private static final boolean printAlisSatis = false;
    private static final boolean generateOutput = false;
    private static final boolean aggregateOrders = true;
    private static final List<String> contractsToFilter = List.of("F_TCELL0525", "F_TCELL0x25");

    private static final NumberFormat formatter = NumberFormat.getNumberInstance(Locale.US);
    static {
        formatter.setMinimumFractionDigits(2);
        formatter.setMaximumFractionDigits(2);
    }

    public static void main(String[] args) {
        try {
            ArrayNode orderList = parseOrderListFromJsonFile();
            
            generateCSV(orderList);

            
            List<Order> orders = populateOrders(orderList);
            orders = aggregateOrders(orders);
            
            Map<String, Summary> summaryMap = new TreeMap<>();
            Summary totalSummary = new Summary();
            populateSummaryMap(orders, summaryMap, totalSummary);
            printDailySummary(summaryMap);
            printCumulativeSummary(totalSummary);


            Map<String, List<Order>> buys = new HashMap<>();
            Map<String, List<Order>> sells = new HashMap<>();
            generateBuyAndSellList(orders, buys, sells);
            
            processTradesForMatch(buys, sells);
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void populateSummaryMap(List<Order> orders, Map<String, Summary> summaryMap, Summary totalSummary) {
        for (Order order : orders) {
            String key = order.date + "|" + order.contract;
            Summary summary = summaryMap.getOrDefault(key, new Summary());

            int units = order.units;
            double price = order.price;
            double volume = units * price * 100;
            double commission = volume * COMMISSION_RATE;

            if (order.shortLong.equals("KISA")) {
                summary.totalShort += units;
                totalSummary.totalShort += units;
            } else if (order.shortLong.equals("UZUN")) {
                summary.totalLong += units;
                totalSummary.totalLong += units;
            }

            summary.totalUnits += units;
            summary.totalVolume += volume;
            summary.totalCommission += commission;

            totalSummary.totalUnits += units;
            totalSummary.totalVolume += volume;
            totalSummary.totalCommission += commission;

            summaryMap.put(key, summary);
        }
    }

    private static List<Order> populateOrders(ArrayNode orderList) {
    	List<Order> orders = new ArrayList<Order>();
        for (JsonNode order : orderList) {
            String rawDate = order.path("TRANSACTION_DATE").asText();
            String date = rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
            String shortLong = order.path("SHORT_LONG").asText().toUpperCase(Locale.ROOT);
            String contract = order.path("CONTRACT").asText();
            int units = (int) order.path("UNITS").asDouble();
            double price = order.path("PRICE").asDouble();
            
            if(contractsToFilter.isEmpty() || contractsToFilter.contains(contract))
            	orders.add(new Order(date, contract, shortLong, units, price));
        }
        return orders;
    }
    
    private static List<Order> aggregateOrders(List<Order> orders) {
    	if(!aggregateOrders)
    		return orders;
    	
        Map<String, Order> aggregatedMap = new LinkedHashMap<>();
        for (Order order : orders) {
            String key = order.contract + "|" + order.shortLong + "|" + order.price;
            if (aggregatedMap.containsKey(key)) {
                Order existing = aggregatedMap.get(key);
                existing.units = existing.units + order.units;
            } else {
                // Yeni nesne kopyası ile ekliyoruz ki orijinal liste etkilenmesin
                aggregatedMap.put(key, new Order(order.date,order.contract,order.shortLong,order.units,order.price));
            }
        }
        return new ArrayList<>(aggregatedMap.values());
    }

    
    private static void processTradesForMatch(Map<String, List<Order>> buys, Map<String, List<Order>> sells) {
    	
        int successfulUnits = 0;
        double totalProfit = 0.0;
        for (String key : buys.keySet()) {
            List<Order> buyList = buys.getOrDefault(key, Collections.emptyList());
            List<Order> sellList = sells.getOrDefault(key, Collections.emptyList());
            Collections.sort(buyList);
            Collections.sort(sellList);

            if(printAlisSatis) {
	            System.out.println("\n" + key + " Alış İşlemler:");
	            for (Order fail : buyList) System.out.println("- " + fail);
	
	            System.out.println("\n" + key + " Satış İşlemler:");
	            for (Order fail : sellList) System.out.println("- " + fail);
            }
            System.out.println("\nBaşarılı:");
            for (int b = 0; b < buyList.size(); b++) {
                Order buy = buyList.get(b);
                for (int s = 0; s < sellList.size(); s++) {
                    Order sell = sellList.get(s);
                    if (sell.remaining() == 0) continue;

                    if (sell.price - buy.price > MARJ_MIN && sell.price - buy.price < MARJ_MAX) {
                        int substractUnit = Math.min(buy.remaining(), sell.remaining());
                        sell.matchedUnits += substractUnit;
                        buy.matchedUnits += substractUnit;

                        System.out.print("--" + String.format("Tür: %s | Units: %d/%d | Fiyat: %.2f", buy.contract, substractUnit, buy.units, buy.price));
                        System.out.println("-" + String.format("Tür: %s | Units: %d/%d | Fiyat: %.2f", sell.contract, substractUnit, sell.units, sell.price));

                        successfulUnits += substractUnit;
                        totalProfit += (substractUnit * (sell.price - buy.price) * 100);

                        if (buy.remaining() == 0) break;
                    }
                }
            }

            System.out.printf("\n" + key + " Başarılı Kontrat Adedi : %d%n", successfulUnits);
            System.out.printf(key + " Toplam Kar             : %.2f TL%n", totalProfit);

        	int totalRemaining = 0;
        	double totalAmount = 0;
            System.out.print("\n" + key + " Başarısız Alış İşlemler:");
            for (Order fail : buyList)
                if (fail.remaining() > 0) {
                	System.out.print("\n- " + fail);
                	totalRemaining += fail.remaining();
                	totalAmount    += fail.remaining()*fail.price;
                }
            System.out.println("\n" + key + " Başarısız Alış => Toplam Adet:" + totalRemaining + ", Average:" + formatter.format(totalAmount / totalRemaining));

            
        	totalRemaining = 0;
        	totalAmount = 0;
            System.out.print("\n" + key + " Başarısız Satış İşlemler:");
            for (Order fail : sellList)
                if (fail.remaining() > 0) {
                	System.out.print("\n- " + fail);
                	totalRemaining += fail.remaining();
                	totalAmount    += fail.remaining()*fail.price;
                }
            System.out.println("\n" + key + " Başarısız Satış => Toplam Adet:"+totalRemaining + ", Average:" + formatter.format(totalAmount / totalRemaining));

        }
    }

    private static void generateBuyAndSellList(List<Order> orders, Map<String, List<Order>> buys, Map<String, List<Order>> sells) {
        for (Order o : orders) {
            String key = o.contract;
            if (o.shortLong.equals("UZUN")) {
                buys.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
            } else if (o.shortLong.equals("KISA")) {
                sells.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
            }
        }
    }

    private static void printCumulativeSummary(Summary totalSummary) {
        System.out.println("\nToplam Kümülatif Özet Bilgiler:");
        System.out.printf("%-18s: %d%n", "Toplam Short", totalSummary.totalShort);
        System.out.printf("%-18s: %d%n", "Toplam Long", totalSummary.totalLong);
        System.out.printf("%-18s: %d%n", "Toplam Units", (int) totalSummary.totalUnits);
        System.out.printf("%-18s: %s%n", "Toplam Hacim", formatter.format(totalSummary.totalVolume));
        System.out.printf("%-18s: %s%n", "Toplam Komisyon", formatter.format(totalSummary.totalCommission));
    }

    private static void printDailySummary(Map<String, Summary> summaryMap) {
        System.out.println("\nGünlük Özet Bilgiler:");
        System.out.printf("%-12s | %-15s | %-7s | %-7s | %-10s | %-15s | %-15s%n", "Tarih", "Kontrat", "Short", "Long", "Units", "Hacim", "Komisyon");

        for (Map.Entry<String, Summary> entry : summaryMap.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String date = parts[0];
            String contract = parts[1];
            Summary s = entry.getValue();
            System.out.printf("%-12s | %-15s | %-7d | %-7d | %-10d | %15s | %15s%n", date, contract, s.totalShort,
                    s.totalLong, (int) s.totalUnits, formatter.format(s.totalVolume),
                    formatter.format(s.totalCommission));
        }
    }

    private static ArrayNode parseOrderListFromJsonFile() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(new File(INPUT_FILE));
        ArrayNode orderList = (ArrayNode) root.path("RESULT").path("HistoricOrderLists");
        return orderList;
    }

    private static void generateCSV(ArrayNode orderList) throws IOException {
    	if(!generateOutput)
    		return;
    	
        try (FileWriter csvWriter = new FileWriter(OUTPUT_FILE)) {
            csvWriter.append("date,contract,shortLong,units,price\n");

            for (JsonNode order : orderList) {
                String rawDate = order.path("TRANSACTION_DATE").asText();
                String date = rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
                String shortLong = order.path("SHORT_LONG").asText().toUpperCase(Locale.ROOT);
                String contract = order.path("CONTRACT").asText();
                int units = (int) order.path("UNITS").asDouble();
                double price = order.path("PRICE").asDouble();

                csvWriter.append(String.format(Locale.US, "%s,%s,%s,%d,%.2f\n", date, contract, shortLong, units, price));
            }

            System.out.println("CSV dosyası başarıyla oluşturuldu: output.csv");
        }
    }


    static class Order implements Comparable<Order> {
        String date;
        String contract;
        String shortLong;
        int units;
        double price;
        int matchedUnits;

        public Order(String date, String contract, String shortLong, int units, double price) {
            this.date = date;
            this.contract = contract;
            this.shortLong = shortLong;
            this.units = units;
            this.matchedUnits = 0;
            this.price = price;
        }

        public void units (int i) {
			// TODO Auto-generated method stub
			
		}

		public int remaining() {
            return this.units - this.matchedUnits;
        }

        @Override
        public String toString() {
            return String.format("Tür: %s | Units: %d/%d | Fiyat: %.2f", shortLong, remaining(), units, price);
        }

        @Override
        public int compareTo(Order other) {
            int priceCompare = Double.compare(this.price, other.price);
            if (priceCompare != 0) return priceCompare;
            return Integer.compare(this.units, other.units);
        }
    }

    static class Summary {
        int totalShort = 0;
        int totalLong = 0;
        double totalUnits = 0.0;
        double totalVolume = 0.0;
        double totalCommission = 0.0;
    }
}
