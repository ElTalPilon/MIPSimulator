/*	Clase Bloque, que se usar� para la memoria cach�
 * 	
 * 	
 */	
public class Bloque {
	private final int tamBloque = 4; //todo bloque tiene 4 palabras, que son 16 enteros
	private int[] bloque; //cada bloque contiene 'tamBloque' enteros
	private int etiqueta; //etiqueta del bloque de memoria que contiene
	private char estado;//'c' para compartido, 'm' para modificado
	
	//constructor de la clase Bloque
	public Bloque(){
		etiqueta =-1; //como aun no contiene ningun bloque de memoria, se inicializa en -1
		estado = 'v'; //por defecto el estado del bloque es 'v' de vacio porque aun no se le carga un bloque de memoria
		bloque = new int[tamBloque];
		//inicializa el bloque con ceros
		for(int i=0; i<tamBloque; ++i){
			bloque[i] = 0;
		}//fin del for
		
	}//fin del constructor
	
	public char getEstado(){
		return estado;
	}
	
	public void setEstado(char status){
		estado = status;
	}
	
	public int getValor(int indice){
		return bloque[indice];
	}
	
	//set de una posicion unica del bloque
	public void setBloquePos(int pos, int valor){
		bloque[pos] = valor;
	}// fin setBloque
	
	public int getEtiqueta(){
		return etiqueta;
	}
	
	public void setEtiqueta(int bloqueMem){
		etiqueta = bloqueMem;
	}
	
	//set de todas las posiciones del bloque (el bloque completo)
	//recibe la posicion de memoria a partir de la cual hace el seteo
	
}//fin de la clase
