import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Clase que simula un registro de un procesador MIPS.
 *
 */
public class Register {
	private int content;
	public Lock lock;
	
	/**
	 * Crea una instancia de clase Register.
	 */
	public Register() {
		content = -1;
		lock = new ReentrantLock();
	}
	
	public void set(int content){
		this.content = content;
	}

	public int get(){
		return content;
	}
}
