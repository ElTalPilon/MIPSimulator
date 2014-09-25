
/**
 * Clase que maneja las diferentes etapas del
 * pipeline de la máquina MIPS.
 */
public class Stage implements Runnable {

	private int stage;
	public static final int ID = 0;
	public static final int IF = 1;
	public static final int EX = 2;
	public static final int M  = 3;
	public static final int WB = 4;
	
	/**
	 * Crea una nueva instancia de Stage, con su respectivo tipo (stage)
	 * @param stage - La etapa del pipeline que hará
	 */
	public Stage(int stage){
		this.stage = stage;
	}
	
	/**
	 * Ejecuta la función del pipeline respectiva a su etapa.
	 */
	@Override
	public void run() {
		switch(stage){
			case ID:
				ID();
			break;
			case IF:
				IF();
			break;
			case EX:
				EX();
			break;
			case M:
				M();
			break;
			case WB:
				WB();
			break;
		}
	}
	
	private void IF(){
		System.out.print("Hola mundo! - IF");
	}
	
	private void ID(){
		System.out.print("Hola mundo! - IF");
	}
	
	private void EX() {
		System.out.print("Hola mundo! - IF");
	}
	
	private void M() {
		System.out.print("Hola mundo! - IF");
	}

	private void WB() {
		System.out.print("Hola mundo! - IF");
	}
}
