package sexy.kostya.events;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Created by RINES on 20.02.17.
 */
public class EventManager {

    private static boolean SYNC;
    private static boolean INITIALIZED = false;
    private static Map<Class<Event>, Set<Handler>> HANDLERS;

    /**
     * The first method that must be called before using the whole event system.
     * @param sync whether or not you're going to handle events synchronously or not.
     */
    public static void initialize(boolean sync) {
        HANDLERS = (SYNC = sync) ? new HashMap<>() : new ConcurrentHashMap<>();
        INITIALIZED = true;
    }

    /**
     * Register given listener (all events handling method within it).
     * @param listener
     */
    @SuppressWarnings("unchecked")
    public static void register(IListener listener) {
        if(!INITIALIZED)
            throw new EventSystemNotInitializedException();
        Class<? extends IListener> clazz = listener.getClass();
        Class<Event> event = Event.class;
        for(Method m : clazz.getDeclaredMethods()) {
            if(m.getParameterCount() != 1 || !m.isAnnotationPresent(EventHandler.class))
                continue;
            Class<?> param = m.getParameterTypes()[0];
            if(!event.isAssignableFrom(param))
                continue;
            Set<Handler> handlers = HANDLERS.get(param);
            if(handlers == null) {
                handlers = new TreeSet<>(Comparator.comparing(Handler::priority));
                if(!SYNC)
                    handlers = Collections.synchronizedSet(handlers);
                HANDLERS.put((Class<Event>) param, handlers);
            }
            EventHandler annotation = m.getAnnotation(EventHandler.class);
            handlers.add(new Handler(annotation.priority(), annotation.ignoreCancelled(), constructConsumer(listener, m)));
        }
    }

    /**
     * Call given event (invoke all methods in registered listeners, which are handling this event).
     * Doesn't invoke handle-methods for any parents of the given event.
     * @param event
     */
    public static void call(Event event) {
        if(!INITIALIZED)
            throw new EventSystemNotInitializedException();
        Set<Handler> handlers = HANDLERS.get(event.getClass());
        if(handlers == null)
            return;
        CancellableEvent ce = event instanceof CancellableEvent ? (CancellableEvent) event : null;
        for(Handler h : handlers) {
            if(h.ignoreCancelled && ce != null && ce.isCancelled())
                continue;
            h.consumer.accept(event);
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Event> constructConsumer(IListener listener, Method method) {
        try {
            MethodHandles.Lookup lookup = constructLookup(listener.getClass());
            return (Consumer<Event>) LambdaMetafactory.metafactory(
                    lookup,
                    "accept",
                    MethodType.methodType(Consumer.class, listener.getClass()),
                    MethodType.methodType(void.class, Object.class),
                    lookup.unreflect(method),
                    MethodType.methodType(void.class, method.getParameterTypes()[0])
            ).getTarget().invoke(listener);
        }catch(Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    /**
     * We are in need of our own Lookup creation with an argument of given owner class, because we want to be able
     * to execute private and any other non-public methods.
     * @param owner owner class.
     * @return lookup with owner class privileges.
     * @throws Exception if something is wrong (?)
     */
    private static MethodHandles.Lookup constructLookup(Class<?> owner) throws Exception {
        Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(owner);
        }finally {
            constructor.setAccessible(false);
        }
    }

    private static class Handler {

        private final byte priority;
        private final boolean ignoreCancelled;
        private final Consumer<Event> consumer;

        Handler(byte priority, boolean ignoreCancelled, Consumer<Event> consumer) {
            this.priority = priority;
            this.ignoreCancelled = ignoreCancelled;
            this.consumer = consumer;
        }

        private byte priority() {
            return this.priority;
        }

    }

    public static class EventSystemNotInitializedException extends IllegalStateException {

        private EventSystemNotInitializedException() {}

    }

}
