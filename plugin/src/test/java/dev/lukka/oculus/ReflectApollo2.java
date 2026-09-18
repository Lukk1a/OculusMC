package dev.lukka.oculus;
import java.lang.reflect.Method;
public class ReflectApollo2 {
    public static void main(String[] args) throws Exception {
        System.out.println("Methods of Waypoint:");
        for(Method m : Class.forName("com.lunarclient.apollo.module.waypoint.Waypoint").getMethods()) {
            System.out.println(m.getName());
        }
        System.out.println("Methods of Title:");
        for(Method m : Class.forName("com.lunarclient.apollo.module.title.Title").getMethods()) {
            System.out.println(m.getName());
        }
        System.out.println("Methods of StaffModModule:");
        for(Method m : Class.forName("com.lunarclient.apollo.module.staffmod.StaffModModule").getMethods()) {
            System.out.println(m.getName());
        }
        System.out.println("Methods of WaypointModule:");
        for(Method m : Class.forName("com.lunarclient.apollo.module.waypoint.WaypointModule").getMethods()) {
            System.out.println(m.getName());
        }
    }
}
