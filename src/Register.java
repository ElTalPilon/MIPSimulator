import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Clase que simula un registro de un procesador MIPS.
 *
 */
public class Register {
	private int value;
	public Lock lock;
	
	/**
	 * Crea una instancia de clase Register.
	 */
	public Register() {
		value = 0;
		lock = new ReentrantLock();
	}
	
	public void set(int val){
		this.value = val;
	}

	public int get(){
		return value;
	}
}
