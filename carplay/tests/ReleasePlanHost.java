// SPDX-License-Identifier: GPL-3.0-or-later
import hmgapi.engine.*;
import java.nio.file.*;
import java.util.*;
public final class ReleasePlanHost {
 static Map<String,Object> read(Path p)throws Exception{return Json.object(new String(Files.readAllBytes(p),"UTF-8"));}
 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
  Path up=Paths.get(args[0]),out=Paths.get(args[1]),wireless=Paths.get(args[2]);
  for(String[] car:new String[][]{{"NEEV","hyundai/neev/usa/260507"},{"NEEV23","hyundai/neev/usa/260507"},{"CVEV","kia/ev6/usa/260507"},{"CEEV","hyundai/ceev/usa/260903"}}){
   Path map=up.resolve("maps/"+car[1]);Map<String,Object> meta=read(map.resolve("car.json"));Map<String,Object> points=new LinkedHashMap<String,Object>();
   for(Object app:(List<Object>)meta.get("apps")){
    Path base=up.resolve("maps/base/"+app+".json"),local=map.resolve(app+".json");
    if(Files.isRegularFile(base))points.putAll(read(base));
    if(Files.isRegularFile(local))points.putAll(read(local));
   }
   Map<String,Map<String,Object>> mods=new LinkedHashMap<String,Map<String,Object>>();mods.put("wireless-carplay-aa",read(wireless.resolve("wireless-carplay-aa/mod.json")));mods.put("egmp-carplay-ev-routing",read(out.resolve("mod/mod.json")));
   HookPlan plan=HookPlan.of(mods,points.keySet(),Collections.<String,String>emptyMap());
   if(!plan.refused.isEmpty())throw new AssertionError(plan.refused);
   if(!plan.order.equals(Arrays.asList("wireless-carplay-aa","egmp-carplay-ev-routing")))throw new AssertionError(plan.order);
   for(String id:new String[]{"iap2.service.on_bind","iap2.service.native_handle","iap2.service.native_lock","iap2.init_info.use_vehicle_information","iap2.init_info.use_vehicle_status"})if(!points.containsKey(id))throw new AssertionError(id);
   Signing.Result signed=Signing.verify(out.resolve("mod").toFile(),"b45fb2d9-e6e8-83a2-bce9-cd5c40d2232d");if(!signed.ok)throw new AssertionError(signed.error);
   System.out.println("PASS official HMG-API "+car[0]+" map, shared wireless/EV hook plan and signed profile");
  }
 }
}
